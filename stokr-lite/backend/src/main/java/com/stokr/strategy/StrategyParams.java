package com.stokr.strategy;

import java.math.BigDecimal;

/**
 * Configurable parameters for strategy execution.
 * Loaded from strategy's params_schema JSON + user overrides.
 */
public record StrategyParams(
        // ORB params
        int orbPeriodMinutes,
        double volumeMultiplier,

        // VWAP params
        double vwapDeviationPct,
        double volumeConfirmMultiplier,

        // Gap Fill params
        double minGapPct,
        double maxGapPct,
        long volumeThreshold,

        // Common params
        double stopLossPct,
        double targetPct
) {
    public static StrategyParams defaults() {
        return new StrategyParams(
                15, 2.0,       // ORB
                0.3, 1.5,      // VWAP
                1.0, 3.0, 50000, // Gap Fill
                0.2, 0.6       // Common: 0.2% SL (₹30 on ₹15K), 0.6% target (3:1 R:R)
        );
    }

    public BigDecimal getStopLossPrice(BigDecimal entry, Signal.Side side) {
        double factor = side == Signal.Side.BUY
                ? 1.0 - (stopLossPct / 100.0)
                : 1.0 + (stopLossPct / 100.0);
        return entry.multiply(BigDecimal.valueOf(factor));
    }

    public BigDecimal getTargetPrice(BigDecimal entry, Signal.Side side) {
        double factor = side == Signal.Side.BUY
                ? 1.0 + (targetPct / 100.0)
                : 1.0 - (targetPct / 100.0);
        return entry.multiply(BigDecimal.valueOf(factor));
    }
}
