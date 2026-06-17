package com.stokr.filter;

import java.math.BigDecimal;

/**
 * 0.1 Volatility Sufficiency (ATR Check)
 * The stock's natural volatility must be large enough for price to reach target.
 */
public class VolatilityFilter {

    /**
     * @param atrPct    ATR as % of price for expected hold period (e.g., 15-min ATR%)
     * @param targetPct target as % of entry price (absolute, e.g., 0.4 for 0.4%)
     * @return true if stock has enough volatility to reach target
     */
    public static boolean hasSufficientVolatility(BigDecimal atrPct, BigDecimal targetPct) {
        if (atrPct == null || targetPct == null) return false;
        BigDecimal threshold = targetPct.multiply(BigDecimal.valueOf(1.5));
        return atrPct.compareTo(threshold) >= 0;
    }

    /**
     * Returns a score 0-100 based on how much volatility exceeds threshold.
     */
    public static double score(BigDecimal atrPct, BigDecimal targetPct) {
        if (atrPct == null || targetPct == null || targetPct.compareTo(BigDecimal.ZERO) == 0) return 0;
        BigDecimal ratio = atrPct.divide(targetPct, 4, java.math.RoundingMode.HALF_UP);
        double raw = ratio.doubleValue() * 50; // 1.5x = 75, 2.0x = 100
        return Math.min(100, Math.max(0, raw));
    }
}
