package com.stokr.broker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class DhanAdapter implements BrokerAdapter {

    @Value("${broker.dhan.api-key:}")
    private String apiKey;

    @Value("${broker.dhan.redirect-uri:http://localhost:8080/api/brokers/dhan/callback}")
    private String redirectUri;

    private static final String DHAN_AUTH_URL = "https://api.dhan.co/oauth/authorize?client_id=";

    @Override
    public String getBrokerName() {
        return "DHAN";
    }

    @Override
    public String getAuthUrl() {
        return DHAN_AUTH_URL + apiKey + "&redirect_uri=" + redirectUri;
    }

    @Override
    public String[] exchangeToken(String requestToken) {
        log.info("Exchanging Dhan auth code for access token");
        // TODO: Implement Dhan OAuth2 token exchange
        throw new UnsupportedOperationException("Dhan token exchange not yet implemented");
    }

    @Override
    public BrokerOrderResponse placeOrder(String accessToken, BrokerOrderRequest request) {
        log.info("Placing Dhan order: {} {} {} qty={}",
                request.side(), request.symbol(), request.orderType(), request.quantity());
        // TODO: POST https://api.dhan.co/v2/orders
        throw new UnsupportedOperationException("Dhan order placement not yet implemented");
    }

    @Override
    public void cancelOrder(String accessToken, String orderId) {
        log.info("Cancelling Dhan order: {}", orderId);
        // TODO: DELETE https://api.dhan.co/v2/orders/{orderId}
    }

    @Override
    public List<BrokerPosition> getPositions(String accessToken) {
        log.info("Fetching Dhan positions");
        // TODO: GET https://api.dhan.co/v2/positions
        return Collections.emptyList();
    }

    @Override
    public BigDecimal getAvailableMargin(String accessToken) {
        log.info("Fetching Dhan margins");
        return BigDecimal.ZERO;
    }

    @Override
    public String getOrderStatus(String accessToken, String orderId) {
        log.info("Fetching Dhan order status: {}", orderId);
        return "UNKNOWN";
    }
}
