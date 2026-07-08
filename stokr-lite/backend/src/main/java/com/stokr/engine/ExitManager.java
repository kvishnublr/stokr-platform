package com.stokr.engine;

import com.stokr.broker.*;
import com.stokr.oms.*;
import com.stokr.risk.DailyPnlTracker;
import com.stokr.risk.ErrorLogService;
import com.stokr.strategy.StrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExitManager {

    private final OrderService orderService;
    private final PositionService positionService;
    private final BrokerService brokerService;
    private final ErrorLogService errorLogService;
    private final PaperBroker paperBroker;
    private final DailyPnlTracker dailyPnlTracker;
    private final StrategyService strategyService;

    /**
     * Square off a position. Returns true if the exit order was placed successfully.
     * Signal status must only be updated by the caller AFTER this returns true.
     */
    public boolean squareOffPosition(Deployment deployment, Position position, BigDecimal exitPrice) {
        if (position.getQuantity() == null || position.getQuantity() == 0) return false;

        String side = position.getQuantity() > 0 ? "SELL" : "BUY";
        int qty = Math.abs(position.getQuantity());

        log.info("Squaring off position for deployment {}: {} {} qty={}",
                deployment.getId(), side, position.getSymbol(), qty);

        try {
            var order = orderService.createOrder(deployment.getId(), position.getSymbol(),
                    side, qty, exitPrice, "MARKET");

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
                    .symbol(position.getSymbol())
                    .exchange("NSE")
                    .side("SELL".equals(side) ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY)
                    .quantity(qty)
                    .price(exitPrice.doubleValue())
                    .orderType(BrokerOrderRequest.OrderType.LIMIT)
                    .productType(productType)
                    .build();

            BrokerOrderResponse response = adapter.placeOrder(accessToken, request);

            // One retry on transient failure
            if (!response.isSuccess()) {
                log.warn("Exit attempt 1 failed for {} — retrying in 200ms: {}",
                        position.getSymbol(), response.message());
                try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                response = adapter.placeOrder(accessToken, request);
            }

            if (response.isSuccess()) {
                // Track realized P&L: (exitPrice - avgPrice) × signed qty
                BigDecimal pnl = exitPrice.subtract(position.getAvgPrice())
                        .multiply(BigDecimal.valueOf(position.getQuantity()));
                dailyPnlTracker.addPnl(deployment.getId(), pnl);

                orderService.completeOrder(order, response.orderId(), exitPrice, qty);
                log.info("Position closed: deployment {} {} qty={} exit={} pnl={}",
                        deployment.getId(), position.getSymbol(), qty, exitPrice, pnl);
                return true;
            } else {
                orderService.rejectOrder(order, response.message());
                errorLogService.logError(deployment.getId(), "EXIT_REJECTED",
                        response.message(), null, "ERROR");
                return false;
            }
        } catch (Exception e) {
            log.error("Exit order failed for deployment {} {}", deployment.getId(), position.getSymbol(), e);
            errorLogService.logError(deployment.getId(), "EXIT_ERROR", e);
            return false;
        }
    }
}
