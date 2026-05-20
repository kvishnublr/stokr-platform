package com.stokr.oms.domain;

/**
 * Execution channel. {@link #SIMULATED} is used for deterministic backtests/replay; {@link #PAPER} for isolated
 * simulated broker fills; {@link #LIVE} for real broker routing.
 */
public enum ExecutionMode {
    SIMULATED,
    PAPER,
    LIVE,
    /** Simultaneously executes both a PAPER and a LIVE leg for divergence tracking. */
    BOTH
}
