package com.stokr.broker.api;

import com.stokr.broker.model.BrokerPosition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * User-scoped broker position fetch (requires stored OAuth session).
 */
public interface BrokerUserPositionsSource {

    boolean supportsVendor(String brokerVendor);

    Optional<List<BrokerPosition>> fetchPositions(UUID userId, String brokerVendor);
}
