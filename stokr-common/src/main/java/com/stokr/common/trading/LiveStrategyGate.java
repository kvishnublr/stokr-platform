package com.stokr.common.trading;

import java.util.UUID;

/**
 * Optional integration hook from strategy lifecycle — implemented in the runtime bundle (execution module).
 */
public interface LiveStrategyGate {

    /**
     * Validates that a LIVE strategy runtime may be started for this definition key.
     *
     * @throws com.stokr.common.exception.BadRequestException when not allowed
     */
    void assertLiveRuntimeAllowed(UUID userId, String strategyKey);
}
