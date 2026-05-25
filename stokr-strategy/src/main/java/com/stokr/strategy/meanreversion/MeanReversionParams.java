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

    // V1: Aggressive Range Fade (tighter thresholds for stronger signals)
    // Updated: Tighten RSI thresholds to 25/75 (stronger extremes)
    // Tighten range width to 0.9% (only true coiled springs)
    // Boost confidence to 0.80 (more selective)
    public static final MeanReversionParams V1 = new MeanReversionParams(
            StrategyKeys.MEAN_REVERSION_RANGE_FADE,
            "1.1.0",
            new BigDecimal("25"),  // Changed from 30: stricter oversold (RSI < 25 = extreme)
            new BigDecimal("75"),  // Changed from 70: stricter overbought (RSI > 75 = extreme)
            new BigDecimal("0.9"), // Changed from 1.2: tighter range (compressed springs only)
            new BigDecimal("0.80") // Changed from 0.72: higher confidence threshold
    );

    // V2: Moderate Range Fade (balanced thresholds for consistent signals)
    // Updated: Slightly tighten RSI to 30/70, range to 1.2%
    // Higher confidence for better quality
    public static final MeanReversionParams V2 = new MeanReversionParams(
            StrategyKeys.MEAN_REVERSION_V2,
            "2.1.0",
            new BigDecimal("30"),  // Changed from 35: stricter oversold
            new BigDecimal("70"),  // Changed from 65: stricter overbought
            new BigDecimal("1.2"), // Changed from 1.45: tighter range
            new BigDecimal("0.75") // Changed from 0.68: higher confidence
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
