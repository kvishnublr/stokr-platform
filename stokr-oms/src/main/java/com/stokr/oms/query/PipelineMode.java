package com.stokr.oms.query;

/**
 * LIVE = production orders ({@code backtest_run_id} is null).
 * BACKTEST / REPLAY = pipeline tied to a backtest run.
 */
public enum PipelineMode {
    ALL,
    LIVE,
    BACKTEST
}
