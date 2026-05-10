package com.stokr.broker.model;

import java.math.BigDecimal;

public record BrokerOrderRequest(
        String symbol,
        String side,
        String orderType,
        BigDecimal quantity,
        BigDecimal limitPrice,
        String clientOrderId
) {
}
