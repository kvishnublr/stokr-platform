package com.stokr.filter;

import java.math.BigDecimal;

/**
 * 0.6 Index Alignment
 * A stock's move rarely sustains against the index.
 */
public class IndexAlignmentFilter {

    public enum Direction { LONG, SHORT }

    public static boolean isAligned(BigDecimal stockChangePct, BigDecimal niftyChangePct, Direction dir) {
        if (stockChangePct == null) return false;
        double stock = stockChangePct.doubleValue();
        double nifty = niftyChangePct != null ? niftyChangePct.doubleValue() : 0;
        return switch (dir) {
            case LONG -> stock > 0 && stock > nifty;
            case SHORT -> stock < 0 && stock < nifty;
        };
    }

    public static double score(BigDecimal stockChangePct, BigDecimal niftyChangePct, Direction dir) {
        if (stockChangePct == null) return 0;
        double stock = stockChangePct.doubleValue();
        double nifty = niftyChangePct != null ? niftyChangePct.doubleValue() : 0;
        double diff = Math.abs(stock - nifty);
        boolean aligned = switch (dir) {
            case LONG -> stock > 0 && stock > nifty;
            case SHORT -> stock < 0 && stock < nifty;
        };
        if (!aligned) return 0;
        return Math.min(100, 50 + diff * 20);
    }
}
