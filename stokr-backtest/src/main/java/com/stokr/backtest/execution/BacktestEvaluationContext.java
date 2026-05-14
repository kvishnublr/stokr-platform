package com.stokr.backtest.execution;

import com.stokr.strategy.meanreversion.runtime.MeanReversionReplayState;

/**
 * Per-bar inputs for {@link com.stokr.backtest.strategy.BacktestStrategyPlugin} evaluation.
 */
public record BacktestEvaluationContext(
        ExecutionContext execution,
        MeanReversionReplayState meanReversionState,
        String pipeline
) {
}
