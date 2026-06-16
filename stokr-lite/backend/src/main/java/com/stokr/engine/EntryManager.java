package com.stokr.engine;

import com.stokr.broker.*;
import com.stokr.oms.OrderService;
import com.stokr.risk.*;
import com.stokr.strategy.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

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

    public void processEntrySignal(Deployment deployment, Signal signal) {
        log.info("Processing entry signal for deployment {}: {} {} @ {}",
                deployment.getId(), signal.side(), signal.symbol(), signal.entryPrice());

        // Dedup check
        if (!idempotencyService.tryAcquire(
                deployment.getId().toString(), signal.symbol(), signal.side().name())) {
            log.info("Duplicate signal suppressed for deployment {} {} {}",
                    deployment.getId(), signal.symbol(), signal.side());
            return;
        }

        // Calculate quantity based on capital and entry price
        int quantity = calculateQuantity(deployment.getCapital(), signal.entryPrice());
        if (quantity <= 0) {
            log.warn("Calculated quantity is 0 for deployment {}", deployment.getId());
            return;
        }

        // Risk check (simplified - would need full context in production)
        RiskContext riskContext = new RiskContext(
                deployment.getId(), deployment.getUserId(), signal.symbol(),
                quantity, signal.entryPrice(), 0, BigDecimal.ZERO,
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

        // Place order
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
