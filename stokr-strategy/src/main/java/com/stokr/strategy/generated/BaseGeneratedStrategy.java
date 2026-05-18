package com.stokr.strategy.generated;

import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;

/**
 * Optional base class for admin-generated strategy templates.
 * Provides convenience helpers so developers can focus on signal logic.
 *
 * Usage: extend this class in your generated strategy and implement
 * {@code TradingStrategy.evaluate(StrategyContext)}.
 *
 * This class intentionally has no Spring bean wiring — generated subclasses
 * declare their own dependencies via constructor injection.
 */
public abstract class BaseGeneratedStrategy {

    /**
     * Helper: emit a BUY signal with a reason string.
     */
    protected StrategySignal bullishSignal(StrategyContext context, String reason) {
        return new StrategySignal(SignalType.BUY, context.symbol(), null, reason);
    }

    /**
     * Helper: emit a SELL signal.
     */
    protected StrategySignal bearishSignal(StrategyContext context, String reason) {
        return new StrategySignal(SignalType.SELL, context.symbol(), null, reason);
    }

    /**
     * Helper: emit a HOLD — strategy conditions not met, no action.
     */
    protected StrategySignal hold(StrategyContext context) {
        return new StrategySignal(SignalType.HOLD, context.symbol(), null, null);
    }

    /**
     * Helper: emit an EXIT signal.
     */
    protected StrategySignal exit(StrategyContext context, String reason) {
        return new StrategySignal(SignalType.EXIT, context.symbol(), null, reason);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Indicator utilities
    // ──────────────────────────────────────────────────────────────────────

    /** Returns the highest value in an array (useful for N-bar high). */
    protected double max(double[] values) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : values) if (v > m) m = v;
        return m;
    }

    /** Returns the lowest value in an array (useful for N-bar low). */
    protected double min(double[] values) {
        double m = Double.POSITIVE_INFINITY;
        for (double v : values) if (v < m) m = v;
        return m;
    }

    /** Simple moving average over the last n values. */
    protected double sma(double[] series, int n) {
        if (series.length < n) return Double.NaN;
        double sum = 0;
        for (int i = series.length - n; i < series.length; i++) sum += series[i];
        return sum / n;
    }

    /** EMA — exponential moving average; returns the last value. */
    protected double ema(double[] series, int period) {
        if (series.length < period) return Double.NaN;
        double k = 2.0 / (period + 1);
        double val = series[0];
        for (int i = 1; i < series.length; i++) val = series[i] * k + val * (1 - k);
        return val;
    }

    /** RSI (Wilder's smoothed) — returns last RSI value in range 0-100. */
    protected double rsi(double[] closes, int period) {
        if (closes.length <= period) return Double.NaN;
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double d = closes[i] - closes[i - 1];
            if (d >= 0) gain += d; else loss -= d;
        }
        gain /= period;
        loss /= period;
        for (int i = period + 1; i < closes.length; i++) {
            double d = closes[i] - closes[i - 1];
            gain = (gain * (period - 1) + Math.max(d, 0)) / period;
            loss = (loss * (period - 1) + Math.max(-d, 0)) / period;
        }
        if (loss == 0) return 100;
        return 100 - (100.0 / (1 + gain / loss));
    }
}
