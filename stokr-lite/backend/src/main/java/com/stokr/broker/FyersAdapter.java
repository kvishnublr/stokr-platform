package com.stokr.broker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class FyersAdapter implements BrokerAdapter {

    @Value("${broker.fyers.api-key:}")
    private String apiKey;

    @Value("${broker.fyers.redirect-uri:http://localhost:8080/api/brokers/fyers/callback}")
    private String redirectUri;

    private static final String FYERS_AUTH_URL = "https://api-t1.fyers.in/api/v3/generate-authcode?client_id=";

    @Override
    public String getBrokerName() {
        return "FYERS";
    }

    @Override
    public String getAuthUrl() {
        return FYERS_AUTH_URL + apiKey + "&redirect_uri=" + redirectUri
                + "&response_type=code&state=stokr";
    }

    @Override
    public String[] exchangeToken(String requestToken) {
        log.info("Exchanging Fyers auth code for access token");
        // TODO: POST https://api-t1.fyers.in/api/v3/validate-authcode
        throw new UnsupportedOperationException("Fyers token exchange not yet implemented");
    }

    @Override
    public BrokerOrderResponse placeOrder(String accessToken, BrokerOrderRequest request) {
        log.info("Placing Fyers order: {} {} {} qty={}",
                request.side(), request.symbol(), request.orderType(), request.quantity());
        // TODO: POST https://api-t1.fyers.in/api/v3/orders
        throw new UnsupportedOperationException("Fyers order placement not yet implemented");
    }

    @Override
    public void cancelOrder(String accessToken, String orderId) {
        log.info("Cancelling Fyers order: {}", orderId);
    }

    @Override
    public List<BrokerPosition> getPositions(String accessToken) {
        log.info("Fetching Fyers positions");
        return Collections.emptyList();
    }

    @Override
    public BigDecimal getAvailableMargin(String accessToken) {
        return BigDecimal.ZERO;
    }

    @Override
    public String getOrderStatus(String accessToken, String orderId) {
        return "UNKNOWN";
    }
}
