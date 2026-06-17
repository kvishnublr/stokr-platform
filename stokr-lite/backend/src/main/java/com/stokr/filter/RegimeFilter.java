package com.stokr.filter;

import java.math.BigDecimal;

/**
 * 0.5 Regime Detection (ADX)
 * The market regime determines whether your strategy type will work.
 */
public class RegimeFilter {

    public static final String TREND_FOLLOWING = "TREND_FOLLOWING";
    public static final String MEAN_REVERSION = "MEAN_REVERSION";

    public static boolean isRegimeValid(BigDecimal adx, String strategyType) {
        if (adx == null || strategyType == null) return false;
        double adxVal = adx.doubleValue();
        return switch (strategyType) {
            case TREND_FOLLOWING -> adxVal > 25;
            case MEAN_REVERSION -> adxVal < 20;
            default -> false;
        };
    }

    public static double score(BigDecimal adx, String strategyType) {
        if (adx == null || strategyType == null) return 0;
        double adxVal = adx.doubleValue();
        return switch (strategyType) {
            case TREND_FOLLOWING -> adxVal > 25 ? Math.min(100, (adxVal - 25) * 4) : 0;
            case MEAN_REVERSION -> adxVal < 20 ? Math.min(100, (20 - adxVal) * 5) : 0;
            default -> 0;
        };
    }

    public static String classifyStrategy(String scannerName) {
        String name = scannerName != null ? scannerName.toUpperCase() : "";
        if (name.contains("BREAKOUT") || name.contains("MOMENTUM") || name.contains("SPIKE")
                || name.contains("IGNITION") || name.contains("CASCADE")) {
            return TREND_FOLLOWING;
        }
        return MEAN_REVERSION;
    }
}
