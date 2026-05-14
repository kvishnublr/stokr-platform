package com.stokr.backtest.domain;

/**
 * Async backtest job lifecycle (see {@code backtest_jobs}).
 */
public enum BacktestJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
