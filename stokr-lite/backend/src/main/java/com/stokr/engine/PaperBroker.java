package com.stokr.engine;

import com.stokr.broker.BrokerAdapter;
import com.stokr.broker.BrokerOrderRequest;
import com.stokr.broker.BrokerOrderResponse;
import com.stokr.broker.BrokerPosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * Paper broker that simulates order execution for paper trading.
 */
@Slf4j
@Component
public class PaperBroker implements BrokerAdapter {

    @Override
    public String getBrokerName() { return "PAPER"; }

    @Override
    public BrokerOrderResponse placeOrder(String accessToken, BrokerOrderRequest request) {
        log.info("[PAPER] {} {} {} qty={} @ {}",
                request.side(), request.symbol(), request.orderType(),
                request.quantity(), request.price());
        // Simulate instant fill at market price
        String orderId = "PAPER-" + System.currentTimeMillis();
        return new BrokerOrderResponse(orderId, "COMPLETE", "Paper fill");
    }

    @Override
    public void cancelOrder(String accessToken, String orderId) {
        log.info("[PAPER] Cancelled order {}", orderId);
    }

    @Override
    public List<BrokerPosition> getPositions(String accessToken) {
        return Collections.emptyList();
    }

    @Override
    public BigDecimal getAvailableMargin(String accessToken) {
        return new BigDecimal("1000000"); // Unlimited paper money
    }

    @Override
    public String getOrderStatus(String accessToken, String orderId) {
        return "COMPLETE";
    }

    @Override
    public String getAuthUrl() { return ""; }

    @Override
    public String[] exchangeToken(String requestToken) { return new String[]{"paper-token"}; }
}
