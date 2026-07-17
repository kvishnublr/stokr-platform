package com.stokr.broker;

import java.math.BigDecimal;

public record BrokerOrderResponse(
        String orderId,
        String status,
        String message,
        double averagePrice
) {
    public BrokerOrderResponse(String orderId, String status, String message) {
        this(orderId, status, message, 0.0);
    }
    public boolean isSuccess() {
        return "COMPLETE".equalsIgnoreCase(status) || "OPEN".equalsIgnoreCase(status);
    }
}
