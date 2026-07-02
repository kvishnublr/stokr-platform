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
        double targetPct,

        // Cash Ignition params (configurable, sweepable in backtest)
        int ignitionScoreThreshold,
        int ignitionTimeStopCandles,
        double ignitionMaxSlPct,
        double ignitionVolumeMultiplier,
        double ignitionAtrMultiplier,
        int ignitionMaxTradesPerDay,
        int ignitionMaxLossesPerDay,
        long ignitionCooldownAfterLossMs,
        long ignitionCooldownAfterWinMs,
        double ignitionTrailTriggerPct,
        double ignitionTrailDistancePct,
        double ignitionPartialExitPct
) {
    public static StrategyParams defaults() {
        return new StrategyParams(
                15, 2.0,       // ORB
                0.3, 2.0,      // VWAP
                1.0, 3.0, 50000, // Gap Fill
                0.2, 0.4,      // 0.2% SL, 0.4% target = 2:1 R:R
                // Cash Ignition defaults
                7,              // score threshold (sweep 6-9)
                5,              // time-stop candles (sweep 3-8)
                0.8,            // max SL distance %
                2.5,            // volume multiplier (sweep 2.0-3.5)
                1.7,            // ATR multiplier (sweep 1.4-2.2)
                5,              // max trades/day
                3,              // max losses/day → stop
                300_000,        // 5 min cooldown after loss
                120_000,        // 2 min cooldown after win
                0.3,            // trail trigger %
                0.3,            // trail distance %
                50.0            // partial exit % at 1R
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
