package com.stokr.chartink;

import com.stokr.broker.*;
import com.stokr.engine.PaperBroker;
import com.stokr.engine.SignalEntity;
import com.stokr.engine.SignalRepository;
import com.stokr.risk.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Executes Chartink signals that pass Movement Assurance.
 * Places orders via the user's active broker and tracks positions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChartinkExecutionService {

    private final BrokerService brokerService;
    private final BrokerAccountRepository brokerAccountRepository;
    private final PaperBroker paperBroker;
    private final ChartinkPositionRepository positionRepository;
    private final SignalRepository signalRepository;
    private final RiskEngine riskEngine;
    private final MinTradeGapRule minTradeGapRule;
    private final StrategyConsecutiveLossRule consecutiveLossRule;

    @Value("${chartink.execution.enabled:true}")
    private boolean executionEnabled;

    @Value("${chartink.execution.max-positions:3}")
    private int maxPositions;

    @Value("${chartink.execution.capital:15000}")
    private BigDecimal capital;

    @Value("${chartink.execution.default-user-id:1}")
    private Long defaultUserId;

    @Value("${chartink.execution.mode:PAPER}")
    private String mode;

    /**
     * Execute a signal that passed Movement Assurance.
     */
    @Transactional
    public void execute(SignalEntity signal) {
        if (!executionEnabled) {
            log.info("Chartink execution disabled. Signal {} queued but not executed.", signal.getId());
            signal.setStatus("QUEUED");
            signalRepository.save(signal);
            return;
        }

        String symbol = signal.getSymbol();
        String side = signal.getSide().name();

        // Check for existing open position in this symbol
        if (positionRepository.existsBySymbolAndStatus(symbol, "OPEN")) {
            log.info("Chartink: Already have open position in {}, skipping entry", symbol);
            signal.setStatus("SKIPPED_POSITION_EXISTS");
            signalRepository.save(signal);
            return;
        }

        // Check max positions
        long openCount = positionRepository.countByStatus("OPEN");
        if (openCount >= maxPositions) {
            log.info("Chartink: Max positions ({}) reached, skipping {}", maxPositions, symbol);
            signal.setStatus("SKIPPED_MAX_POSITIONS");
            signalRepository.save(signal);
            return;
        }

        // Calculate quantity
        BigDecimal price = signal.getEntryPrice() != null ? signal.getEntryPrice() : BigDecimal.ZERO;
        int quantity = calculateQuantity(price);
        if (quantity <= 0) {
            log.warn("Chartink: Calculated quantity is 0 for {} @ {}", symbol, price);
            signal.setStatus("REJECTED_ZERO_QTY");
            signalRepository.save(signal);
            return;
        }

        // Risk evaluation
        BigDecimal totalDeployed = positionRepository.findByStatusOrderByCreatedAtDesc("OPEN").stream()
                .map(p -> p.getEntryPrice().multiply(BigDecimal.valueOf(p.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        RiskContext riskContext = new RiskContext(
                null, defaultUserId, symbol, quantity, price,
                (int) openCount, BigDecimal.ZERO, capital, totalDeployed,
                maxPositions, new BigDecimal("225"), 1000, 0
        );

        RiskRule.RiskDecision riskDecision = riskEngine.evaluate(riskContext);
        if (!riskDecision.passed()) {
            log.warn("Chartink: Risk check failed for {}: {}", symbol, riskDecision.reason());
            signal.setStatus("RISK_REJECTED");
            signalRepository.save(signal);
            return;
        }

        // Strategy consecutive loss check
        String strategyType = signal.getScannerName() != null ? signal.getScannerName() : "UNKNOWN";
        RiskRule.RiskDecision lossDecision = consecutiveLossRule.checkStrategy(defaultUserId, strategyType);
        if (!lossDecision.passed()) {
            log.warn("Chartink: {} — skipping {}", lossDecision.reason(), symbol);
            signal.setStatus("STRATEGY_PAUSED");
            signalRepository.save(signal);
            return;
        }

        // Place order
        try {
            BrokerOrderResponse response = placeBrokerOrder(symbol, side, quantity);

            if (response != null && response.isSuccess()) {
                // Create position
                ChartinkPosition position = ChartinkPosition.builder()
                        .signalId(signal.getId())
                        .symbol(symbol)
                        .side(side)
                        .quantity(quantity)
                        .entryPrice(price)
                        .avgPrice(price)
                        .stopLoss(signal.getStopLoss())
                        .target(signal.getTarget())
                        .highestPrice(price)
                        .lowestPrice(price)
                        .trailingStop(signal.getStopLoss()) // initial trailing = SL
                        .status("OPEN")
                        .build();
                positionRepository.save(position);

                signal.setStatus("EXECUTED");
                signalRepository.save(signal);

                log.info("Chartink: EXECUTED {} {} {} qty={} @ {} | brokerOrderId={}",
                        symbol, side, quantity, price, response.orderId());

                minTradeGapRule.recordTrade(defaultUserId, symbol);
            } else {
                String msg = response != null ? response.message() : "No response from broker";
                log.error("Chartink: Order rejected for {}: {}", symbol, msg);
                signal.setStatus("REJECTED");
                signalRepository.save(signal);
            }
        } catch (Exception e) {
            log.error("Chartink: Order placement failed for {}", symbol, e);
            signal.setStatus("ERROR");
            signalRepository.save(signal);
        }
    }

    private BrokerOrderResponse placeBrokerOrder(String symbol, String side, int quantity) {
        BrokerOrderRequest request = BrokerOrderRequest.builder()
                .symbol(symbol)
                .exchange("NSE")
                .side("BUY".equals(side) ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL)
                .quantity(quantity)
                .orderType(BrokerOrderRequest.OrderType.MARKET)
                .productType("MIS")
                .build();

        if ("PAPER".equalsIgnoreCase(mode)) {
            return paperBroker.placeOrder("paper", request);
        }

        // LIVE mode: find active broker account for default user
        var accounts = brokerAccountRepository.findByUserIdAndBrokerNameAndStatus(
                defaultUserId, "ZERODHA", "ACTIVE");
        if (accounts.isEmpty()) {
            log.warn("No active Zerodha account for user {}, falling back to paper", defaultUserId);
            return paperBroker.placeOrder("paper", request);
        }

        BrokerAccount account = accounts.get(0);
        BrokerAdapter adapter = brokerService.getAdapter(account.getBrokerName());
        return adapter.placeOrder(account.getAccessToken(), request);
    }

    private int calculateQuantity(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) return 0;
        BigDecimal perPosition = capital.divide(BigDecimal.valueOf(maxPositions), 0, RoundingMode.DOWN);
        return perPosition.divide(price, 0, RoundingMode.DOWN).intValue();
    }

    @Transactional
    public void closePosition(String symbol, String reason) {
        var posOpt = positionRepository.findBySymbolAndStatus(symbol, "OPEN");
        if (posOpt.isEmpty()) {
            log.warn("Chartink: No open position in {} to close", symbol);
            return;
        }

        ChartinkPosition position = posOpt.get();
        String closeSide = "BUY".equals(position.getSide()) ? "SELL" : "BUY";
        int qty = position.getQuantity();

        try {
            BrokerOrderResponse response = placeBrokerOrder(symbol, closeSide, qty);

            if (response != null && response.isSuccess()) {
                // Mark position closed
                position.setStatus("CLOSED");
                position.setExitReason(reason);
                position.setClosedAt(Instant.now());
                positionRepository.save(position);

                // Update signal status
                signalRepository.findById(position.getSignalId()).ifPresent(s -> {
                    s.setStatus("EXITED");
                    signalRepository.save(s);
                });

                log.info("Chartink: CLOSED {} {} qty={} | reason={}", symbol, closeSide, qty, reason);
            } else {
                String msg = response != null ? response.message() : "No response";
                log.error("Chartink: Exit order failed for {}: {}", symbol, msg);
            }
        } catch (Exception e) {
            log.error("Chartink: Exit placement failed for {}", symbol, e);
        }
    }
}
