package com.stokr.engine;

import com.stokr.broker.*;
import com.stokr.oms.OrderService;
import com.stokr.oms.Position;
import com.stokr.oms.PositionService;
import com.stokr.risk.*;
import com.stokr.strategy.Signal;
import com.stokr.strategy.StrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntryManager {

    private final OrderService orderService;
    private final BrokerService brokerService;
    private final RiskEngine riskEngine;
    private final IdempotencyService idempotencyService;
    private final ErrorLogService errorLogService;
    private final PaperBroker paperBroker;
    private final PositionService positionService;
    private final DailyPnlTracker dailyPnlTracker;
    private final StrategyService strategyService;

    // Cooldown: tracks last successful signal time per deployment+symbol
    private final ConcurrentHashMap<String, Long> lastSignalTime = new ConcurrentHashMap<>();

    /**
     * Process an entry signal. Returns true if the order was placed successfully.
     * Caller should update signal status based on the return value.
     */
    public boolean processEntrySignal(Deployment deployment, Signal signal) {
        log.info("Processing entry signal for deployment {}: {} {} @ {}",
                deployment.getId(), signal.side(), signal.symbol(), signal.entryPrice());

        // Price filter: only trade stocks between ₹100 and ₹3000
        double entryPx = signal.entryPrice().doubleValue();
        if (entryPx < 100.0 || entryPx > 3000.0) {
            log.info("Deployment {} skipping {} — price ₹{} outside ₹100–₹3000 range",
                    deployment.getId(), signal.symbol(), signal.entryPrice());
            return false;
        }

        // Check if deployment already has an open position in this symbol
        List<Position> openPositions = positionService.getOpenPositions(deployment.getId());
        boolean hasPositionInSymbol = openPositions.stream()
                .anyMatch(p -> p.getSymbol().equals(signal.symbol()));
        if (hasPositionInSymbol) {
            log.info("Deployment {} already has open position in {}, skipping entry",
                    deployment.getId(), signal.symbol());
            return false;
        }

        // Check max positions limit (default: 5 per deployment)
        if (openPositions.size() >= 5) {
            log.info("Deployment {} has {} open positions (max 5), skipping entry",
                    deployment.getId(), openPositions.size());
            return false;
        }

        // Dedup check
        if (!idempotencyService.tryAcquire(
                deployment.getId().toString(), signal.symbol(), signal.side().name())) {
            log.info("Duplicate signal suppressed for deployment {} {} {}",
                    deployment.getId(), signal.symbol(), signal.side());
            return false;
        }

        // Each position gets full capital (position value = capital; MIS margin is at broker level)
        int quantity = calculateQuantity(deployment.getCapital(), signal.entryPrice());
        if (quantity <= 0) {
            log.warn("Calculated quantity is 0 for deployment {}", deployment.getId());
            return false;
        }

        // Today's realized P&L from DailyPnlTracker (updated on each exit)
        BigDecimal todayPnl = dailyPnlTracker.getTodayPnl(deployment.getId());

        // totalDeployedCapital = what is currently in open positions
        BigDecimal deployedCapital = deployment.getCapital()
                .multiply(BigDecimal.valueOf(openPositions.size()));

        // availableCapital = maxPositions × capital (allows up to 5 simultaneous full-capital positions)
        BigDecimal availableCapital = deployment.getCapital().multiply(BigDecimal.valueOf(5));

        // Cooldown: last successful signal time for this deployment+symbol
        String cooldownKey = deployment.getId() + ":" + signal.symbol();
        long lastMs = lastSignalTime.getOrDefault(cooldownKey, 0L);

        RiskContext riskContext = new RiskContext(
                deployment.getId(), deployment.getUserId(), signal.symbol(),
                quantity, signal.entryPrice(), openPositions.size(), todayPnl,
                availableCapital,
                deployedCapital,
                5, new BigDecimal("5000"), 1000, lastMs);

        RiskRule.RiskDecision riskDecision = riskEngine.evaluate(riskContext);
        if (!riskDecision.passed()) {
            log.info("Risk check failed for deployment {}: {}",
                    deployment.getId(), riskDecision.reason());
            errorLogService.logError(deployment.getId(), "RISK_REJECT",
                    riskDecision.reason(), null, "WARN");
            return false;
        }

        // Place order with one retry on failure
        try {
            var order = orderService.createOrder(deployment.getId(), signal.symbol(),
                    signal.side().name(), quantity, signal.entryPrice(), "MARKET");

            BrokerAdapter adapter = deployment.isLive()
                    ? brokerService.getAdapter(
                        brokerService.getBrokerAccount(deployment.getBrokerAccountId(),
                                deployment.getUserId()).getBrokerName())
                    : paperBroker;

            String accessToken = deployment.isLive()
                    ? brokerService.getBrokerAccount(deployment.getBrokerAccountId(),
                            deployment.getUserId()).getAccessToken()
                    : "paper";

            // Use NRML (positional) for daily strategies, MIS (intraday) for intraday
            String productType = "MIS";
            try {
                var strategy = strategyService.getStrategy(deployment.getStrategyId());
                if ("DAILY".equalsIgnoreCase(strategy.getTimeframe())) {
                    productType = "NRML";
                }
            } catch (Exception ignored) {}

            BrokerOrderRequest request = BrokerOrderRequest.builder()
                    .symbol(signal.symbol())
                    .exchange("NSE")
                    .side(signal.side() == Signal.Side.BUY
                            ? BrokerOrderRequest.Side.BUY
                            : BrokerOrderRequest.Side.SELL)
                    .quantity(quantity)
                    .price(signal.entryPrice().doubleValue())
                    .orderType(BrokerOrderRequest.OrderType.LIMIT)
                    .productType(productType)
                    .build();

            BrokerOrderResponse response = adapter.placeOrder(accessToken, request);

            // One retry on transient failure
            if (!response.isSuccess()) {
                log.warn("Order attempt 1 failed for {} — retrying in 200ms: {}",
                        signal.symbol(), response.message());
                try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                response = adapter.placeOrder(accessToken, request);
            }

            if (response.isSuccess()) {
                orderService.completeOrder(order, response.orderId(),
                        signal.entryPrice(), quantity);
                lastSignalTime.put(cooldownKey, System.currentTimeMillis());
                return true;
            } else {
                orderService.rejectOrder(order, response.message());
                errorLogService.logError(deployment.getId(), "ORDER_REJECTED",
                        response.message(), null, "ERROR");
                return false;
            }
        } catch (Exception e) {
            log.error("Order placement failed for deployment {}", deployment.getId(), e);
            errorLogService.logError(deployment.getId(), "ORDER_ERROR", e);
            return false;
        }
    }

    private int calculateQuantity(BigDecimal capital, BigDecimal price) {
        if (price.compareTo(BigDecimal.ZERO) <= 0) return 0;
        return capital.divide(price, 0, java.math.RoundingMode.DOWN).intValue();
    }
}
