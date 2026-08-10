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

    // Calculate actual hedged margin for NFO positions from broker
    default BigDecimal getHedgedMargin(String accessToken, String underlying, String futSymbol, String ceSymbol, String peSymbol, int qty) {
        return null; // fallback to hardcoded estimate
    }

    // OAuth flow
    String getAuthUrl();

    String[] exchangeToken(String requestToken);
    // Returns [accessToken, refreshToken] or just [accessToken]
}
