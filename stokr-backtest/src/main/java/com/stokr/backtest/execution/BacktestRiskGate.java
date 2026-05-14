package com.stokr.backtest.execution;

/**
 * Centralized runtime risk checks for backtests (extensible; PR-3 foundation).
 */
public final class BacktestRiskGate {

    public boolean allowNewExposure(ExecutionContext ctx, int simulatedOpenPositions) {
        if (simulatedOpenPositions < 0) {
            return false;
        }
        return true;
    }
}
