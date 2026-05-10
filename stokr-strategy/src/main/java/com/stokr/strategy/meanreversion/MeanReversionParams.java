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

    public static final MeanReversionParams V1 = new MeanReversionParams(
            StrategyKeys.MEAN_REVERSION_RANGE_FADE,
            "1.0.0",
            new BigDecimal("30"),
            new BigDecimal("70"),
            new BigDecimal("1.2"),
            new BigDecimal("0.72")
    );

    public static final MeanReversionParams V2 = new MeanReversionParams(
            StrategyKeys.MEAN_REVERSION_V2,
            "2.0.0",
            new BigDecimal("35"),
            new BigDecimal("65"),
            new BigDecimal("1.45"),
            new BigDecimal("0.68")
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
