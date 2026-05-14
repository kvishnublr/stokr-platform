package com.stokr.backtest.engine;

import java.util.UUID;

/**
 * Optional hooks for long-running replay (async jobs, UI progress). Implementations must be cheap;
 * heavy work should be throttled internally.
 */
public interface ReplayProgressCallback {

    /**
     * Invoked once the {@link com.stokr.backtest.domain.BacktestRun} row is persisted (before candle loop).
     */
    default void onRunPersisted(UUID runId) {
    }

    /**
     * Invoked after bar count is known and before the candle loop begins.
     */
    default void onTotalsKnown(UUID runId, int totalBars) {
    }

    /**
     * After each candle processed (includes bars skipped for resume fast-forward).
     *
     * @param barsCompleted count of candles iterated so far (1..totalBars)
     */
    default void onBarProgress(UUID runId, int barsCompleted, int totalBars) {
    }

    /**
     * Cooperative cancellation for async jobs; synchronous replay passes {@code null} callback or returns false.
     */
    default boolean isCancelled() {
        return false;
    }
}
