package com.stokr.chartink;

import com.stokr.broker.*;
import com.stokr.engine.PaperBroker;
import com.stokr.engine.SignalEntity;
import com.stokr.engine.SignalRepository;
import com.stokr.marketdata.MarketDataService;
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
 * Uses per-trader configuration for capital, position limits, and risk.
 * Supports both PAPER and LIVE modes.
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
    private final TraderConfigService traderConfigService;
    private final MarketDataService marketDataService;

    @Value("${chartink.execution.default-user-id:1}")
    private Long defaultUserId;

    /**
     * Execute a signal that passed Movement Assurance.
     * Uses per-trader config for all decisions.
     */
    @Transactional
    public void execute(SignalEntity signal) {
        Long userId = signal.getUserId() != null ? signal.getUserId() : defaultUserId;
        TraderConfig config = traderConfigService.getConfig(userId);

        if (!config.isEnabled()) {
            log.info("Chartink trading disabled for user {}. Signal {} queued.", userId, signal.getId());
            signal.setStatus("QUEUED");
            signalRepository.save(signal);
            return;
        }

        String symbol = signal.getSymbol();
        String side = signal.getSide().name();
        BigDecimal price = signal.getEntryPrice() != null ? signal.getEntryPrice() : BigDecimal.ZERO;

        // Price filter: share price must be between min and max
        if (price.compareTo(config.getMinSharePrice()) < 0 || price.compareTo(config.getMaxSharePrice()) > 0) {
            log.warn("Chartink: Price {} for {} outside trader range [₹{}, ₹{}]",
                    price, symbol, config.getMinSharePrice(), config.getMaxSharePrice());
            signal.setStatus("REJECTED_PRICE_RANGE");
            signalRepository.save(signal);
            return;
        }

        // Check for existing open position in this symbol
        if (positionRepository.existsBySymbolAndStatus(symbol, "OPEN")) {
            log.info("Chartink: Already have open position in {}, skipping entry", symbol);
            signal.setStatus("SKIPPED_POSITION_EXISTS");
            signalRepository.save(signal);
            return;
        }

        // Check max positions per trader
        long openCount = positionRepository.countByStatus("OPEN");
        if (openCount >= config.getMaxPositions()) {
            log.info("Chartink: Max positions ({}) reached for user {}, skipping {}",
                    config.getMaxPositions(), userId, symbol);
            signal.setStatus("SKIPPED_MAX_POSITIONS");
            signalRepository.save(signal);
            return;
        }

        // Calculate quantity using trader config
        int quantity = config.calculateQuantity(price);
        if (quantity <= 0) {
            log.warn("Chartink: Calculated quantity is 0 for {} @ {} (per-trade capital: ₹{})",
                    symbol, price, config.getPerTradeCapital());
            signal.setStatus("REJECTED_ZERO_QTY");
            signalRepository.save(signal);
            return;
        }

        // Risk evaluation
        BigDecimal totalDeployed = positionRepository.findByStatusOrderByCreatedAtDesc("OPEN").stream()
                .map(p -> p.getEntryPrice().multiply(BigDecimal.valueOf(p.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        RiskContext riskContext = new RiskContext(
                null, userId, symbol, quantity, price,
                (int) openCount, BigDecimal.ZERO, config.getCapital(), totalDeployed,
                config.getMaxPositions(), config.getMaxDailyLoss(), 1000, 0
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
        RiskRule.RiskDecision lossDecision = consecutiveLossRule.checkStrategy(userId, strategyType);
        if (!lossDecision.passed()) {
            log.warn("Chartink: {} — skipping {}", lossDecision.reason(), symbol);
            signal.setStatus("STRATEGY_PAUSED");
            signalRepository.save(signal);
            return;
        }

        // Place order
        try {
            BrokerOrderResponse response = placeBrokerOrder(symbol, side, quantity, userId, config);

            if (response != null && response.isSuccess()) {
                // Build SL and target from trader config if signal doesn't have them
                BigDecimal sl = signal.getStopLoss() != null ? signal.getStopLoss()
                        : calculateStopLoss(price, side, config);
                BigDecimal target = signal.getTarget() != null ? signal.getTarget()
                        : calculateTarget(price, side, config);

                ChartinkPosition position = ChartinkPosition.builder()
                        .signalId(signal.getId())
                        .userId(userId)
                        .symbol(symbol)
                        .side(side)
                        .quantity(quantity)
                        .entryPrice(price)
                        .avgPrice(price)
                        .stopLoss(sl)
                        .target(target)
                        .highestPrice(price)
                        .lowestPrice(price)
                        .trailingStop(sl)
                        .status("OPEN")
                        .build();
                positionRepository.save(position);

                signal.setStopLoss(sl);
                signal.setTarget(target);
                signal.setStatus("EXECUTED");
                signalRepository.save(signal);

                log.info("Chartink: EXECUTED {} {} qty={} @ {} | mode={} | brokerOrderId={}",
                        symbol, side, quantity, price, config.getMode(), response.orderId());

                minTradeGapRule.recordTrade(userId, symbol);
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

    private BrokerOrderResponse placeBrokerOrder(String symbol, String side, int quantity,
                                                  Long userId, TraderConfig config) {
        BrokerOrderRequest request = BrokerOrderRequest.builder()
                .symbol(symbol)
                .exchange("NSE")
                .side("BUY".equals(side) ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL)
                .quantity(quantity)
                .orderType(BrokerOrderRequest.OrderType.MARKET)
                .productType("MIS")
                .build();

        if (config.getMode() == TraderConfig.Mode.PAPER) {
            return paperBroker.placeOrder("paper_" + userId, request);
        }

        // LIVE mode: find active broker account for user
        var accounts = brokerAccountRepository.findByUserIdAndBrokerNameAndStatus(
                userId, "ZERODHA", "ACTIVE");
        if (accounts.isEmpty()) {
            log.warn("No active Zerodha account for user {}, falling back to paper", userId);
            return paperBroker.placeOrder("paper_" + userId, request);
        }

        BrokerAccount account = accounts.get(0);
        BrokerAdapter adapter = brokerService.getAdapter(account.getBrokerName());
        return adapter.placeOrder(account.getAccessToken(), request);
    }

    private BigDecimal calculateStopLoss(BigDecimal entry, String side, TraderConfig config) {
        double factor = "BUY".equals(side)
                ? 1.0 - (config.getStopLossPct().doubleValue() / 100.0)
                : 1.0 + (config.getStopLossPct().doubleValue() / 100.0);
        return entry.multiply(BigDecimal.valueOf(factor)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateTarget(BigDecimal entry, String side, TraderConfig config) {
        double factor = "BUY".equals(side)
                ? 1.0 + (config.getTargetPct().doubleValue() / 100.0)
                : 1.0 - (config.getTargetPct().doubleValue() / 100.0);
        return entry.multiply(BigDecimal.valueOf(factor)).setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional
    public void closePosition(String symbol, String reason) {
        var posOpt = positionRepository.findBySymbolAndStatus(symbol, "OPEN");
        if (posOpt.isEmpty()) {
            log.warn("Chartink: No open position in {} to close", symbol);
            return;
        }

        ChartinkPosition position = posOpt.get();
        Long userId = position.getUserId() != null ? position.getUserId() : defaultUserId;
        TraderConfig config = traderConfigService.getConfig(userId);

        String closeSide = "BUY".equals(position.getSide()) ? "SELL" : "BUY";
        int qty = position.getQuantity();

        try {
            BrokerOrderResponse response = placeBrokerOrder(symbol, closeSide, qty, userId, config);

            if (response != null && response.isSuccess()) {
                position.setStatus("CLOSED");
                position.setExitReason(reason);
                position.setClosedAt(Instant.now());

                // Calculate realized PnL for consecutive loss tracking
                BigDecimal exitPrice = marketDataService.getLtp(symbol);
                if (exitPrice != null) {
                    BigDecimal pnl = exitPrice.subtract(position.getAvgPrice())
                            .multiply(BigDecimal.valueOf(
                                    "BUY".equals(position.getSide()) ? qty : -qty));
                    position.setRealizedPnl(pnl);

                    // Record win/loss for consecutive loss rule
                    String scanner = signalRepository.findById(position.getSignalId())
                            .map(SignalEntity::getScannerName)
                            .orElse("UNKNOWN");
                    if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                        consecutiveLossRule.recordWin(userId, scanner);
                    } else {
                        consecutiveLossRule.recordLoss(userId, scanner);
                    }
                }

                positionRepository.save(position);

                signalRepository.findById(position.getSignalId()).ifPresent(s -> {
                    s.setStatus("EXITED");
                    signalRepository.save(s);
                });

                log.info("Chartink: CLOSED {} {} qty={} | reason={} | mode={} | pnl={}",
                        symbol, closeSide, qty, reason, config.getMode(),
                        position.getRealizedPnl());
            } else {
                String msg = response != null ? response.message() : "No response";
                log.error("Chartink: Exit order failed for {}: {}", symbol, msg);
            }
        } catch (Exception e) {
            log.error("Chartink: Exit placement failed for {}", symbol, e);
        }
    }
}
