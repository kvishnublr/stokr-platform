package com.stokr.broker;

public record BrokerOrderResponse(
        String orderId,
        String status,
        String message
) {
    public boolean isSuccess() {
        return "COMPLETE".equalsIgnoreCase(status) || "OPEN".equalsIgnoreCase(status);
    }
}
