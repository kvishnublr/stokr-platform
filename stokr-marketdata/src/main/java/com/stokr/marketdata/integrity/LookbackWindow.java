package com.stokr.marketdata.integrity;

import java.time.Duration;

/**
 * Maximum allowed span between the current bar and a lookback anchor bar.
 */
public enum LookbackWindow {
    FIVE_MINUTE(Duration.ofMinutes(7)),
    THIRTY_MINUTE(Duration.ofMinutes(35));

    private final Duration maxSpan;

    LookbackWindow(Duration maxSpan) {
        this.maxSpan = maxSpan;
    }

    public Duration maxSpan() {
        return maxSpan;
    }
}
