package com.stokr.execution.exchange;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages virtual account margin (funds available for trading).
 * Blocks margin on order entry, releases on position exit.
 */
@Slf4j
public class MarginManager {

    private final BigDecimal startingCapital;
    private BigDecimal freeCash;
    private BigDecimal usedMargin = BigDecimal.ZERO;
    private final BigDecimal marginMultiplier;  // e.g., 5x for intraday
    private final Map<String, BigDecimal> symbolMargin = new ConcurrentHashMap<>();

    public MarginManager(BigDecimal startingCapital, BigDecimal marginMultiplier) {
        this.startingCapital = startingCapital;
        this.freeCash = startingCapital;
        this.marginMultiplier = marginMultiplier;
    }

    /**
     * Check if sufficient margin available for order.
     */
    public boolean checkMarginAvailable(BigDecimal orderValue) {
        BigDecimal availableMargin = getAvailableMargin();
        boolean available = availableMargin.compareTo(orderValue) >= 0;

        if (!available) {
            log.warn("margin.insufficient orderValue={} available={}",
                    orderValue, availableMargin);
        }

        return available;
    }

    /**
     * Block margin for an order.
     */
    public void blockMargin(String symbol, BigDecimal orderValue) {
        if (!checkMarginAvailable(orderValue)) {
            throw new MarginException("Insufficient margin for order: " + orderValue);
        }

        usedMargin = usedMargin.add(orderValue);
        symbolMargin.merge(symbol, orderValue, BigDecimal::add);

        log.info("margin.blocked symbol={} value={} usedMargin={} free={}",
                symbol, orderValue, usedMargin, freeCash);
    }

    /**
     * Release margin when position closes.
     */
    public void releaseMargin(String symbol, BigDecimal orderValue) {
        usedMargin = usedMargin.subtract(orderValue).max(BigDecimal.ZERO);
        symbolMargin.compute(symbol, (k, v) -> v == null ? BigDecimal.ZERO
                : v.subtract(orderValue).max(BigDecimal.ZERO));

        log.info("margin.released symbol={} value={} usedMargin={} free={}",
                symbol, orderValue, usedMargin, freeCash);
    }

    /**
     * Add realized P&L to free cash.
     */
    public void addRealizedPnl(BigDecimal pnl) {
        freeCash = freeCash.add(pnl);
        log.info("margin.pnl_added pnl={} freeCash={}", pnl, freeCash);
    }

    /**
     * Get available margin for new orders.
     * = (free cash * multiplier) - already_used
     */
    public BigDecimal getAvailableMargin() {
        BigDecimal maxMargin = freeCash.multiply(marginMultiplier);
        return maxMargin.subtract(usedMargin).max(BigDecimal.ZERO);
    }

    /**
     * Get margin utilization percentage.
     */
    public BigDecimal getMarginUtilizationPercent() {
        BigDecimal maxMargin = freeCash.multiply(marginMultiplier);
        if (maxMargin.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return usedMargin.divide(maxMargin, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * Get current margin state snapshot.
     */
    public MarginSnapshot getSnapshot() {
        return new MarginSnapshot(
                startingCapital,
                freeCash,
                usedMargin,
                marginMultiplier,
                getAvailableMargin(),
                getMarginUtilizationPercent()
        );
    }

    /**
     * Margin state snapshot.
     */
    @Data
    public static class MarginSnapshot {
        public final BigDecimal startingCapital;
        public final BigDecimal freeCash;
        public final BigDecimal usedMargin;
        public final BigDecimal marginMultiplier;
        public final BigDecimal availableMargin;
        public final BigDecimal utilizationPercent;

        public MarginSnapshot(BigDecimal startingCapital, BigDecimal freeCash,
                            BigDecimal usedMargin, BigDecimal marginMultiplier,
                            BigDecimal availableMargin, BigDecimal utilizationPercent) {
            this.startingCapital = startingCapital;
            this.freeCash = freeCash;
            this.usedMargin = usedMargin;
            this.marginMultiplier = marginMultiplier;
            this.availableMargin = availableMargin;
            this.utilizationPercent = utilizationPercent;
        }
    }

    /**
     * Margin exception.
     */
    public static class MarginException extends RuntimeException {
        public MarginException(String message) {
            super(message);
        }
    }
}
