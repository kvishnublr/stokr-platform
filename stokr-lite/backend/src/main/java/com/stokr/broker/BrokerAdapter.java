package com.stokr.broker;

import java.math.BigDecimal;
import java.util.List;

public interface BrokerAdapter {

    String getBrokerName();

    BrokerOrderResponse placeOrder(String accessToken, BrokerOrderRequest request);

    void cancelOrder(String accessToken, String orderId);

    List<BrokerPosition> getPositions(String accessToken);

    BigDecimal getAvailableMargin(String accessToken);

    String getOrderStatus(String accessToken, String orderId);

    // OAuth flow
    String getAuthUrl();

    String[] exchangeToken(String requestToken);
    // Returns [accessToken, refreshToken] or just [accessToken]
}
