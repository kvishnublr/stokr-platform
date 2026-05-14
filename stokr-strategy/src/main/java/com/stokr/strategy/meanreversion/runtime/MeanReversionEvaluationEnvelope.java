package com.stokr.strategy.meanreversion.runtime;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Execution-aware inputs for deterministic mean-reversion evaluation at a single bar.
 */
public record MeanReversionEvaluationEnvelope(
        MeanReversionRuntimeParams params,
        MeanReversionReplayState state,
        ZoneId zone,
        LocalTime sessionStart,
        LocalTime sessionEnd,
        int barIndex,
        long deterministicSeed,
        String correlationId,
        String primaryTimeframe,
        String higherTimeframe
) {
}
