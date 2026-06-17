package com.stokr.engine;

import com.stokr.broker.*;
import com.stokr.oms.*;
import com.stokr.risk.ErrorLogService;
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

    public void squareOffPosition(Deployment deployment, Position position, BigDecimal exitPrice) {
        if (!position.isOpen()) return;

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

            BrokerOrderRequest request = BrokerOrderRequest.builder()
                    .symbol(position.getSymbol())
                    .exchange("NSE")
                    .side("SELL".equals(side) ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY)
                    .quantity(qty)
                    .orderType(BrokerOrderRequest.OrderType.MARKET)
                    .productType("MIS")
                    .build();

            BrokerOrderResponse response = adapter.placeOrder(accessToken, request);

            if (response.isSuccess()) {
                orderService.completeOrder(order, response.orderId(), exitPrice, qty);
                log.info("Position closed: deployment {} {} qty={} exit={}",
                        deployment.getId(), position.getSymbol(), qty, exitPrice);
            } else {
                orderService.rejectOrder(order, response.message());
                errorLogService.logError(deployment.getId(), "EXIT_REJECTED",
                        response.message(), null, "ERROR");
            }
        } catch (Exception e) {
            log.error("Exit order failed for deployment {} {}", deployment.getId(), position.getSymbol(), e);
            errorLogService.logError(deployment.getId(), "EXIT_ERROR", e);
        }
    }
}
