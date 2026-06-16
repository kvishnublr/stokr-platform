package com.stokr.strategy.sdk;

import java.time.Instant;

/**
 * Minimal replay-safe hooks ??? prefer {@link ReplaySafeStrategy} for full research lifecycle.
 */
public interface DeterministicStrategy {

    /**
     * Called once per decision clock instant (typically candle open in replay).
     */
    default void onCandle(String symbol, Instant barOpenUtc, String timeframe) {
    }

    default void onTick(String symbol, Instant tickUtc, java.math.BigDecimal price) {
    }

    /**
     * Invoked after persistence restores opaque JSON snapshots.
     */
    default void onRecovery(StrategyRestoreContext ctx) {
    }
}
