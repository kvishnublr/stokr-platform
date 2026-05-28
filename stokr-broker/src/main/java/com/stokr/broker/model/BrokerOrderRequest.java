package com.stokr.broker.model;

import java.math.BigDecimal;

public record BrokerOrderRequest(
        String symbol,
        String side,
        String orderType,
        BigDecimal quantity,
        BigDecimal limitPrice,
        String clientOrderId,
        String apiKey,
        String accessToken,
        String exchange,
        String product,
        /** Outbound IP for broker API calls (Zerodha IP whitelisting). NULL = use default server IP. */
        String outboundIp
) {
    /** Backward-compatible constructor for non-live (simulated/paper) execution paths. */
    public BrokerOrderRequest(String symbol, String side, String orderType,
                              BigDecimal quantity, BigDecimal limitPrice, String clientOrderId) {
        this(symbol, side, orderType, quantity, limitPrice, clientOrderId, null, null, null, null, null);
    }

    /** Constructor without outbound IP (backward-compatible). */
    public BrokerOrderRequest(String symbol, String side, String orderType,
                              BigDecimal quantity, BigDecimal limitPrice, String clientOrderId,
                              String apiKey, String accessToken, String exchange, String product) {
        this(symbol, side, orderType, quantity, limitPrice, clientOrderId, apiKey, accessToken, exchange, product, null);
    }
}
