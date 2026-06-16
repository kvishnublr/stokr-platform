package com.stokr.backtest.domain;

/**
 * Explicit terminal classification for async replay jobs ??? avoids silent {@code COMPLETED} with no explainable metrics.
 * <p>Orthogonal to {@link BacktestJobStatus} (queue lifecycle): diagnosis is populated when a job reaches a terminal row.</p>
 */
public enum ReplayTerminalDiagnosis {
    /** No candles in range / preflight failed before meaningful work. */
    NO_DATA,
    /** Candles processed but no persisted signals and no OMS executions ??? strategy path produced nothing observable. */
    NO_SIGNALS,
    /** Signals exist but execution pipeline produced zero executions (routing / bridge / gate). */
    EXECUTION_BLOCKED,
    /** Completed run with no bars advanced (unexpected ??? investigate data + plugin). */
    EMPTY_REPLAY,
    /** Worker or engine exception. */
    FAILED,
    /** Healthy terminal: signals and/or executions and/or closed trades present. */
    COMPLETED
}
