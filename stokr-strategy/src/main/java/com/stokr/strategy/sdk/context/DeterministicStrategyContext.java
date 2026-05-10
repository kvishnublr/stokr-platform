package com.stokr.strategy.sdk.context;

import com.stokr.strategy.sdk.DeterministicClock;

import java.time.Instant;
import java.util.UUID;

/**
 * Decision-time context for a single evaluation instant (replay-safe).
 */
public record DeterministicStrategyContext(
        UUID userId,
        UUID runOrInstanceId,
        String symbol,
        String pipeline,
        String timeframe,
        Instant decisionInstant,
        DeterministicClock clock
) {
}
