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
                0.3, 2.0,      // VWAP
                1.0, 3.0, 50000, // Gap Fill
                0.2, 0.4       // 0.2% SL, 0.4% target = 2:1 R:R. Need 34%+ win rate to profit.
                               // VWAP bounce SL is from VWAP (natural support), target from entry.
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
