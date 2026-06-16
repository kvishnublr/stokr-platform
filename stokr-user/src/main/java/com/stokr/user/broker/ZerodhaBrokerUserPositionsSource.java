package com.stokr.user.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.stokr.broker.api.BrokerUserPositionsSource;
import com.stokr.broker.kite.ZerodhaKitePositionsParser;
import com.stokr.broker.model.BrokerPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ZerodhaBrokerUserPositionsSource implements BrokerUserPositionsSource {

    private final ZerodhaBrokerOperationsService zerodhaBrokerOperationsService;

    @Override
    public boolean supportsVendor(String brokerVendor) {
        return brokerVendor != null && "ZERODHA".equalsIgnoreCase(brokerVendor.trim());
    }

    @Override
    public Optional<List<BrokerPosition>> fetchPositions(UUID userId, String brokerVendor) {
        if (!supportsVendor(brokerVendor)) {
            return Optional.empty();
        }
        try {
            return Optional.of(zerodhaBrokerOperationsService.fetchBrokerPositions(userId));
        } catch (Exception ex) {
            log.warn("zerodha.positions.fetch_failed user={} error={}", userId, ex.getMessage());
            return Optional.empty();
        }
    }
}
