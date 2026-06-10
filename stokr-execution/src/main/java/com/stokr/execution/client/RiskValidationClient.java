package com.stokr.execution.client;

import com.stokr.common.messaging.events.SignalGeneratedEvent;
import com.stokr.common.http.ServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RiskValidationClient {

    @Value("${risk.service.url:http://core-trading:8080}")
    private String riskServiceUrl;

    private final ServiceClient serviceClient;

    public boolean validateSignal(SignalGeneratedEvent signal) {
        try {
            String endpoint = riskServiceUrl + "/api/v1/risk/validate";

            RiskValidationRequest request = RiskValidationRequest.builder()
                    .signalId(signal.getSignalId())
                    .symbol(signal.getSymbol())
                    .side(signal.getSide())
                    .quantity(signal.getQuantity())
                    .aiScore(signal.getAiScore())
                    .executionMode(signal.getExecutionMode())
                    .build();

            RiskValidationResponse response = serviceClient.post(
                    endpoint,
                    request,
                    RiskValidationResponse.class
            );

            boolean passed = response != null && response.isValidationPassed();
            if (!passed) {
                log.warn("Risk validation failed for signal {}: {}",
                        signal.getSignalId(), response != null ? response.getReason() : "null response");
            }
            return passed;

        } catch (Exception e) {
            log.error("Error calling risk validation service for signal {}: {}",
                    signal.getSignalId(), e.getMessage(), e);
            return false;
        }
    }

    // Inner DTOs
    @lombok.Data
    @lombok.Builder
    public static class RiskValidationRequest {
        private String signalId;
        private String symbol;
        private String side;
        private int quantity;
        private double aiScore;
        private String executionMode;
    }

    @lombok.Data
    public static class RiskValidationResponse {
        private boolean validationPassed;
        private String reason;
        private long durationMs;
    }
}
