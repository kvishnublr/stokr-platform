package com.stokr.common.market;

import java.time.Instant;

/**
 * Result of evaluating whether the admin platform market tape is safe for live-scanner coupling.
 */
public record LiveMarketPathAssessment(
        boolean operational,
        /** CONNECTED, DEGRADED, OFFLINE, AUTH_EXPIRED, RECONNECTING, PAUSED */
        String platformTapeState,
        String reason,
        Instant evaluatedAt
) {
}
