package com.stokr.backtest.service;

import java.time.Instant;

/**
 * Counters captured inside the replay candle loop for observability on {@link com.stokr.backtest.domain.BacktestJob}.
 */
public record ReplayLoopTelemetry(
        int candlesExpected,
        int candlesProcessed,
        int signalsEmitted,
        Instant loopStartedAt
) {
    public long durationMs(Instant end) {
        if (loopStartedAt == null || end == null) {
            return -1L;
        }
        return Math.max(0L, end.toEpochMilli() - loopStartedAt.toEpochMilli());
    }
}
