package com.stokr.filter;

import java.math.BigDecimal;

/**
 * 0.4 MicroPrice Direction Confirmation
 * MicroPrice = (bidPrice × askQty + askPrice × bidQty) / (bidQty + askQty)
 * Uses Chartink buyer/seller qty as proxy when order book unavailable.
 */
public class MicroPriceFilter {

    public enum Direction { LONG, SHORT }

    public static BigDecimal microPrice(BigDecimal bidPrice, Long bidQty,
                                         BigDecimal askPrice, Long askQty) {
        if (bidPrice == null || askPrice == null || bidQty == null || askQty == null) return null;
        if (bidQty + askQty == 0) return null;
        BigDecimal numerator = bidPrice.multiply(BigDecimal.valueOf(askQty))
                .add(askPrice.multiply(BigDecimal.valueOf(bidQty)));
        return numerator.divide(BigDecimal.valueOf(bidQty + askQty), 4, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Alternative: use buyer/seller ratio from Chartink as proxy.
     */
    public static boolean isConfirmedByRatio(Long buyerQty, Long sellerQty, Direction dir) {
        if (buyerQty == null || sellerQty == null || sellerQty == 0) return false;
        double ratio = buyerQty / (double) sellerQty;
        return switch (dir) {
            case LONG -> ratio > 1.3;
            case SHORT -> ratio < 0.77; // 1/1.3
        };
    }

    public static boolean isConfirmed(BigDecimal microPrice, BigDecimal ltp, Direction dir) {
        if (microPrice == null || ltp == null) return false;
        return switch (dir) {
            case LONG -> microPrice.compareTo(ltp) > 0;
            case SHORT -> microPrice.compareTo(ltp) < 0;
        };
    }

    public static double score(Long buyerQty, Long sellerQty, Direction dir) {
        if (buyerQty == null || sellerQty == null || sellerQty == 0) return 0;
        double ratio = buyerQty / (double) sellerQty;
        return switch (dir) {
            case LONG -> Math.min(100, Math.max(0, (ratio - 0.5) * 100));
            case SHORT -> Math.min(100, Math.max(0, (1.5 - ratio) * 100));
        };
    }
}
