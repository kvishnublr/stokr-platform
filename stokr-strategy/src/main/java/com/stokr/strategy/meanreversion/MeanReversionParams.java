package com.stokr.strategy.meanreversion;

import com.stokr.strategy.keys.StrategyKeys;

import java.math.BigDecimal;

/**
 * Tunable parameters for range-fade mean reversion variants (deterministic / replay-safe).
 */
public record MeanReversionParams(
        String catalogStrategyKey,
        String strategyVersion,
        BigDecimal rsiBuyMax,
        BigDecimal rsiSellMin,
        BigDecimal maxRangeWidthPct,
        BigDecimal confidenceScore
) {

    // V1: Balanced Range Fade (optimized thresholds for signal generation)
    // Updated: Moderate RSI 30/70 (sweet spot for oversold/overbought)
    // Range width 1.0% (compressed but realistic)
    // High confidence 0.78 (good signal quality)
    public static final MeanReversionParams V1 = new MeanReversionParams(
            StrategyKeys.MEAN_REVERSION_RANGE_FADE,
            "1.2.0",
            new BigDecimal("30"),  // RSI < 30 = oversold (realistic extreme)
            new BigDecimal("70"),  // RSI > 70 = overbought (realistic extreme)
            new BigDecimal("1.0"), // Range width 1.0% (strong compression)
            new BigDecimal("0.78") // High confidence threshold
    );

    // V2: Aggressive Range Fade (wider parameters for more opportunities)
    // Updated: Relaxed RSI to 35/65 (more frequent signals)
    // Range width 1.25% (wider compression allows more trades)
    // Confidence 0.74 (balanced quality vs opportunity)
    public static final MeanReversionParams V2 = new MeanReversionParams(
            StrategyKeys.MEAN_REVERSION_V2,
            "2.2.0",
            new BigDecimal("35"),  // RSI < 35 = moderately oversold
            new BigDecimal("65"),  // RSI > 65 = moderately overbought
            new BigDecimal("1.25"), // Range width 1.25% (more trading opportunities)
            new BigDecimal("0.74") // Moderate confidence (more signals generated)
    );

    public String toSnapshotJson() {
        return "{\"catalogStrategyKey\":\"" + catalogStrategyKey
                + "\",\"strategyVersion\":\"" + strategyVersion
                + "\",\"rsiBuyMax\":\"" + rsiBuyMax.toPlainString()
                + "\",\"rsiSellMin\":\"" + rsiSellMin.toPlainString()
                + "\",\"maxRangeWidthPct\":\"" + maxRangeWidthPct.toPlainString()
                + "\"}";
    }
}
