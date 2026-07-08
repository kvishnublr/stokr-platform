package com.stokr.risk;

import com.stokr.broker.*;
import com.stokr.engine.Deployment;
import com.stokr.engine.PaperBroker;
import com.stokr.oms.OrderService;
import com.stokr.oms.Position;
import com.stokr.oms.PositionService;
import com.stokr.strategy.StrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Handles BTST overnight gap-down exits using MARKET orders.
 * LIMIT orders won't fill after an overnight gap — this ensures execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OvernightGapHandler {

    private final OrderService orderService;
    private final BrokerService brokerService;
    private final PaperBroker paperBroker;
    private final StrategyService strategyService;

    public boolean exitGappedPosition(Deployment deployment, Position position, BigDecimal ltp) {
        if (position.getQuantity() == null || position.getQuantity() == 0) return false;

        String side = position.getQuantity() > 0 ? "SELL" : "BUY";
        int qty = Math.abs(position.getQuantity());

        BigDecimal lossPct = position.getAvgPrice().subtract(ltp)
                .divide(position.getAvgPrice(), 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        log.warn("GAP-DOWN EXIT: {} qty={} entry={} ltp={} loss={}%",
            position.getSymbol(), qty, position.getAvgPrice(), ltp, lossPct);

        try {
            var order = orderService.createOrder(deployment.getId(), position.getSymbol(),
                    side, qty, ltp, "MARKET");

            BrokerAdapter adapter = deployment.isLive()
                    ? brokerService.getAdapter(
                        brokerService.getBrokerAccount(deployment.getBrokerAccountId(),
                                deployment.getUserId()).getBrokerName())
                    : paperBroker;

            String accessToken = deployment.isLive()
                    ? brokerService.getBrokerAccount(deployment.getBrokerAccountId(),
                            deployment.getUserId()).getAccessToken()
                    : "paper";

            // Use NRML for daily strategies (positional), CNC for BTST
            String productType = "CNC";
            try {
                var strategy = strategyService.getStrategy(deployment.getStrategyId());
                if ("DAILY".equalsIgnoreCase(strategy.getTimeframe())) {
                    productType = "NRML";
                }
            } catch (Exception ignored) {}

            BrokerOrderRequest request = BrokerOrderRequest.builder()
                    .symbol(position.getSymbol())
                    .exchange("NSE")
                    .side("SELL".equals(side) ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY)
                    .quantity(qty)
                    .price(0.0)
                    .orderType(BrokerOrderRequest.OrderType.MARKET)
                    .productType(productType)
                    .build();

            BrokerOrderResponse response = adapter.placeOrder(accessToken, request);

            if (!response.isSuccess()) {
                log.warn("Gap exit retry for {}", position.getSymbol());
                try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                response = adapter.placeOrder(accessToken, request);
            }

            if (response.isSuccess()) {
                orderService.completeOrder(order, response.orderId(), ltp, qty);
                log.info("Gap exit OK: {} qty={} ltp={}", position.getSymbol(), qty, ltp);
                return true;
            } else {
                orderService.rejectOrder(order, response.message());
                log.error("Gap exit FAILED: {} {}", position.getSymbol(), response.message());
                return false;
            }
        } catch (Exception e) {
            log.error("Gap exit error: {} {}", position.getSymbol(), e.getMessage());
            return false;
        }
    }
}
