package com.stokr.broker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class ZerodhaAdapter implements BrokerAdapter {

    @Value("${broker.zerodha.api-key:}")
    private String apiKey;

    @Value("${broker.zerodha.api-secret:}")
    private String apiSecret;

    @Value("${broker.zerodha.redirect-uri:http://localhost:8080/api/brokers/zerodha/callback}")
    private String redirectUri;

    private static final String KITE_AUTH_URL = "https://kite.zerodha.com/connect/login?v=3&api_key=";
    private static final String KITE_API_BASE = "https://api.kite.trade";

    @Override
    public String getBrokerName() {
        return "ZERODHA";
    }

    @Override
    public String getAuthUrl() {
        return KITE_AUTH_URL + apiKey;
    }

    @Override
    public String[] exchangeToken(String requestToken) {
        // In production: POST to https://api.kite.trade/session/token
        // with api_key, request_token, and checksum (SHA256 of api_key+request_token+api_secret)
        log.info("Exchanging Zerodha request token for access token");
        // TODO: Implement actual Kite Connect API call
        // For now, return placeholder
        throw new UnsupportedOperationException(
                "Zerodha token exchange requires kiteconnect SDK. Add dependency and implement.");
    }

    @Override
    public BrokerOrderResponse placeOrder(String accessToken, BrokerOrderRequest request) {
        log.info("Placing Zerodha order: {} {} {} qty={}",
                request.side(), request.symbol(), request.orderType(), request.quantity());
        // TODO: POST /orders/regular via Kite Connect API
        throw new UnsupportedOperationException("Zerodha order placement requires kiteconnect SDK");
    }

    @Override
    public void cancelOrder(String accessToken, String orderId) {
        log.info("Cancelling Zerodha order: {}", orderId);
        // TODO: DELETE /orders/regular/{order_id}
    }

    @Override
    public List<BrokerPosition> getPositions(String accessToken) {
        log.info("Fetching Zerodha positions");
        // TODO: GET /portfolio/positions
        return Collections.emptyList();
    }

    @Override
    public BigDecimal getAvailableMargin(String accessToken) {
        log.info("Fetching Zerodha margins");
        // TODO: GET /user/margins
        return BigDecimal.ZERO;
    }

    @Override
    public String getOrderStatus(String accessToken, String orderId) {
        log.info("Fetching Zerodha order status: {}", orderId);
        // TODO: GET /orders/{order_id}
        return "UNKNOWN";
    }
}
