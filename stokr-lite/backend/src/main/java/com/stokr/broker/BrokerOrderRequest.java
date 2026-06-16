package com.stokr.broker;

import lombok.Builder;

@Builder
public record BrokerOrderRequest(
        String symbol,
        String exchange,
        Side side,
        int quantity,
        Double price,
        OrderType orderType,
        String productType
) {
    public enum Side { BUY, SELL }
    public enum OrderType { MARKET, LIMIT, SL, SL_MARKET }
}
