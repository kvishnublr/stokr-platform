package com.stokr.execution.client;

import com.stokr.common.messaging.events.SignalGeneratedEvent;
import com.stokr.common.http.ServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class BrokerIntegrationService {

    @Value("${broker.service.url:http://core-trading:8080}")
    private String brokerServiceUrl;

    private final ServiceClient serviceClient;

    public String submitOrder(SignalGeneratedEvent signal) {
        try {
            String endpoint = brokerServiceUrl + "/api/v1/broker/submit-order";

            BrokerOrderRequest request = BrokerOrderRequest.builder()
                    .signalId(signal.getSignalId())
                    .symbol(signal.getSymbol())
                    .side(signal.getSide())
                    .quantity(signal.getQuantity())
                    .executionMode(signal.getExecutionMode())
                    .orderId(UUID.randomUUID().toString()) // Generate unique order ID
                    .build();

            BrokerOrderResponse response = serviceClient.post(
                    endpoint,
                    request,
                    BrokerOrderResponse.class
            );

            if (response != null && response.isSuccess() && response.getOrderId() != null) {
                log.info("Order placed successfully: {} | Broker Order: {}",
                        signal.getSignalId(), response.getOrderId());
                return response.getOrderId();
            } else {
                log.warn("Broker submission failed for signal {}: {}",
                        signal.getSignalId(), response != null ? response.getErrorMessage() : "null response");
                return null;
            }

        } catch (Exception e) {
            log.error("Error submitting order to broker for signal {}: {}",
                    signal.getSignalId(), e.getMessage(), e);
            return null;
        }
    }

    // Inner DTOs
    @lombok.Data
    @lombok.Builder
    public static class BrokerOrderRequest {
        private String signalId;
        private String symbol;
        private String side;
        private int quantity;
        private String executionMode;
        private String orderId;
    }

    @lombok.Data
    public static class BrokerOrderResponse {
        private boolean success;
        private String orderId;
        private String errorMessage;
        private double price;
        private long durationMs;
    }
}
