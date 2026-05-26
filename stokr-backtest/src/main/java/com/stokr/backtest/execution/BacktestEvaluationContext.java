package com.stokr.backtest.execution;

/**
 * Per-bar inputs for {@link com.stokr.backtest.strategy.BacktestStrategyPlugin} evaluation.
 */
public record BacktestEvaluationContext(
        ExecutionContext execution,
        Object strategyState,
        String pipeline
) {
}
