package com.stokr.execution.replay;

import java.time.Instant;

/**
 * Unified execution context with separate system time and market time.
 * CRITICAL: Trading logic uses marketTime, logging uses systemTime.
 */
public class ExecutionContext {

    private final Instant systemTime;      // actual wall clock
    private Instant marketTime;             // trading time (may be replayed)
    private final String mode;              // LIVE, PAPER, HYBRID, SIMULATED

    public ExecutionContext(String mode) {
        this.systemTime = Instant.now();
        this.marketTime = systemTime;
        this.mode = mode;
    }

    public ExecutionContext(String mode, Instant marketTime) {
        this.systemTime = Instant.now();
        this.marketTime = marketTime;
        this.mode = mode;
    }

    /**
     * Get current time for trading logic (order placement, fill execution, PnL).
     * Returns marketTime in PAPER/SIMULATED mode, systemTime in LIVE mode.
     */
    public Instant getCurrentTime() {
        if ("LIVE".equals(mode)) {
            return systemTime;
        }
        return marketTime;
    }

    /**
     * Get current time for logging and audit trails.
     * Always returns systemTime.
     */
    public Instant getLogTime() {
        return systemTime;
    }

    /**
     * Update market time (for replay).
     */
    public void setMarketTime(Instant time) {
        this.marketTime = time;
    }

    /**
     * Get market time (for diagnostics).
     */
    public Instant getMarketTime() {
        return marketTime;
    }

    /**
     * Assert that we're using correct time source.
     * Prevent accidental time source mixing.
     */
    public void assertTradingTime(Instant used) {
        Instant expected = getCurrentTime();
        if (!used.equals(expected)) {
            throw new IllegalStateException(
                    "Time source mismatch: expected=" + expected + ", actual=" + used
            );
        }
    }
}
