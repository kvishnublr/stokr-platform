package com.stokr.broker.model;

import java.math.BigDecimal;
import java.util.UUID;

public record BrokerOrderResponse(
        UUID brokerOrderId,
        String status,
        String symbol,
        BigDecimal filledQty,
        String externalOrderId
) {
    public BrokerOrderResponse(UUID brokerOrderId, String status, String symbol, BigDecimal filledQty) {
        this(brokerOrderId, status, symbol, filledQty, null);
    }
}
