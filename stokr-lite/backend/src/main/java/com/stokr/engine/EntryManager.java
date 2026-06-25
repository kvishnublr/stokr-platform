package com.stokr.engine;

import com.stokr.broker.*;
import com.stokr.oms.OrderService;
import com.stokr.oms.Position;
import com.stokr.oms.PositionService;
import com.stokr.risk.*;
import com.stokr.strategy.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

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

    public void processEntrySignal(Deployment deployment, Signal signal) {
        log.info("Processing entry signal for deployment {}: {} {} @ {}",
                deployment.getId(), signal.side(), signal.symbol(), signal.entryPrice());

        // Check if deployment already has an open position in this symbol
        List<Position> openPositions = positionService.getOpenPositions(deployment.getId());
        boolean hasPositionInSymbol = openPositions.stream()
                .anyMatch(p -> p.getSymbol().equals(signal.symbol()));
        if (hasPositionInSymbol) {
            log.info("Deployment {} already has open position in {}, skipping entry",
                    deployment.getId(), signal.symbol());
            return;
        }

        // Check max positions limit (default: 5 per deployment)
        if (openPositions.size() >= 5) {
            log.info("Deployment {} has {} open positions (max 5), skipping entry",
                    deployment.getId(), openPositions.size());
            return;
        }

        // Dedup check
        if (!idempotencyService.tryAcquire(
                deployment.getId().toString(), signal.symbol(), signal.side().name())) {
            log.info("Duplicate signal suppressed for deployment {} {} {}",
                    deployment.getId(), signal.symbol(), signal.side());
            return;
        }

        // Calculate quantity based on capital and entry price
        BigDecimal capitalPerPosition = deployment.getCapital()
                .divide(BigDecimal.valueOf(5), 0, java.math.RoundingMode.DOWN); // Max 5 positions
        int quantity = calculateQuantity(capitalPerPosition, signal.entryPrice());
        if (quantity <= 0) {
            log.warn("Calculated quantity is 0 for deployment {}", deployment.getId());
            return;
        }

        // Real daily realized PnL from open positions
        BigDecimal todayPnl = openPositions.stream()
                .map(Position::getRealizedPnl)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        RiskContext riskContext = new RiskContext(
                deployment.getId(), deployment.getUserId(), signal.symbol(),
                quantity, signal.entryPrice(), openPositions.size(), todayPnl,
                BigDecimal.ZERO, deployment.getCapital(),
                3, new BigDecimal("5000"), 100, 0);

        RiskRule.RiskDecision riskDecision = riskEngine.evaluate(riskContext);
        if (!riskDecision.passed()) {
            log.info("Risk check failed for deployment {}: {}",
                    deployment.getId(), riskDecision.reason());
            errorLogService.logError(deployment.getId(), "RISK_REJECT",
                    riskDecision.reason(), null, "WARN");
            return;
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

            BrokerOrderRequest request = BrokerOrderRequest.builder()
                    .symbol(signal.symbol())
                    .exchange("NSE")
                    .side(signal.side() == Signal.Side.BUY
                            ? BrokerOrderRequest.Side.BUY
                            : BrokerOrderRequest.Side.SELL)
                    .quantity(quantity)
                    .orderType(BrokerOrderRequest.OrderType.MARKET)
                    .productType("MIS")
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
            } else {
                orderService.rejectOrder(order, response.message());
                errorLogService.logError(deployment.getId(), "ORDER_REJECTED",
                        response.message(), null, "ERROR");
            }
        } catch (Exception e) {
            log.error("Order placement failed for deployment {}", deployment.getId(), e);
            errorLogService.logError(deployment.getId(), "ORDER_ERROR", e);
        }
    }

    private int calculateQuantity(BigDecimal capital, BigDecimal price) {
        if (price.compareTo(BigDecimal.ZERO) <= 0) return 0;
        return capital.divide(price, 0, java.math.RoundingMode.DOWN).intValue();
    }
}
