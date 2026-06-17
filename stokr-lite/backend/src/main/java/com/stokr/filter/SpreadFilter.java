package com.stokr.filter;

import java.math.BigDecimal;

/**
 * 0.3 Liquidity / Spread Filter
 * The spread must be tight enough that transaction costs don't consume the edge.
 */
public class SpreadFilter {

    public static boolean isSpreadTight(BigDecimal bestBid, BigDecimal bestAsk, String category) {
        if (bestBid == null || bestAsk == null || bestBid.compareTo(BigDecimal.ZERO) <= 0) return false;
        BigDecimal spreadPct = bestAsk.subtract(bestBid)
                .divide(bestBid, 6, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        double maxSpread = switch (category != null ? category.toUpperCase() : "OTHER") {
            case "NIFTY50" -> 0.03;
            case "NIFTY_NEXT_50" -> 0.06;
            case "MIDCAP" -> 0.12;
            default -> 0.20;
        };
        return spreadPct.doubleValue() <= maxSpread;
    }

    public static double score(BigDecimal bestBid, BigDecimal bestAsk, String category) {
        if (bestBid == null || bestAsk == null || bestBid.compareTo(BigDecimal.ZERO) <= 0) return 0;
        BigDecimal spreadPct = bestAsk.subtract(bestBid)
                .divide(bestBid, 6, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        double maxSpread = switch (category != null ? category.toUpperCase() : "OTHER") {
            case "NIFTY50" -> 0.03;
            case "NIFTY_NEXT_50" -> 0.06;
            case "MIDCAP" -> 0.12;
            default -> 0.20;
        };
        double ratio = maxSpread / Math.max(spreadPct.doubleValue(), 0.001);
        return Math.min(100, ratio * 50);
    }
}
