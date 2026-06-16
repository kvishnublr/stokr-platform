package com.stokr.execution.exchange;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * In-memory order book for a single symbol.
 * Maintains BID and ASK sides with FIFO matching.
 */
@Slf4j
public class OrderBook {

    private final String symbol;
    private final NavigableMap<BigDecimal, PriceLevel> bidSide = new TreeMap<>();  // descending by price
    private final NavigableMap<BigDecimal, PriceLevel> askSide = new TreeMap<>();  // ascending by price

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    /**
     * Add a limit order to the book.
     */
    public void addLimitOrder(LimitOrder order) {
        NavigableMap<BigDecimal, PriceLevel> side = order.isBuy() ? bidSide : askSide;
        PriceLevel level = side.computeIfAbsent(order.limitPrice, p -> new PriceLevel(p));
        level.addOrder(order);
        log.debug("orderbook.add symbol={} side={} price={} qty={} orderId={}",
                symbol, order.side, order.limitPrice, order.quantity, order.orderId);
    }

    /**
     * Remove an order from the book.
     */
    public void removeOrder(UUID orderId, String side) {
        NavigableMap<BigDecimal, PriceLevel> sideMap = "BUY".equals(side) ? bidSide : askSide;
        for (PriceLevel level : sideMap.values()) {
            if (level.removeOrder(orderId)) {
                log.debug("orderbook.remove symbol={} side={} orderId={}", symbol, side, orderId);
                if (level.isEmpty()) {
                    sideMap.remove(level.price);
                }
                return;
            }
        }
    }

    /**
     * Get best bid price, or null if no bids.
     */
    public BigDecimal getBestBid() {
        return bidSide.isEmpty() ? null : bidSide.lastKey();  // highest price
    }

    /**
     * Get best ask price, or null if no asks.
     */
    public BigDecimal getBestAsk() {
        return askSide.isEmpty() ? null : askSide.firstKey();  // lowest price
    }

    /**
     * Get current spread (bid-ask).
     */
    public BigDecimal getSpread() {
        BigDecimal bid = getBestBid();
        BigDecimal ask = getBestAsk();
        if (bid == null || ask == null) {
            return null;
        }
        return ask.subtract(bid);
    }

    /**
     * Get available liquidity at each price level on a side.
     */
    public List<LiquidityLevel> getLiquidity(String side, int levels) {
        List<LiquidityLevel> result = new ArrayList<>();
        NavigableMap<BigDecimal, PriceLevel> sideMap = "BUY".equals(side) ? bidSide : askSide;
        int count = 0;

        if ("BUY".equals(side)) {
            // BUY side: show best (highest) prices first
            for (PriceLevel level : sideMap.descendingMap().values()) {
                if (count >= levels) break;
                result.add(new LiquidityLevel(level.price, level.getTotalQuantity()));
                count++;
            }
        } else {
            // ASK side: show best (lowest) prices first
            for (PriceLevel level : sideMap.values()) {
                if (count >= levels) break;
                result.add(new LiquidityLevel(level.price, level.getTotalQuantity()));
                count++;
            }
        }

        return result;
    }

    /**
     * Get bid-ask spread and midpoint.
     */
    public SpreadInfo getSpreadInfo() {
        BigDecimal bid = getBestBid();
        BigDecimal ask = getBestAsk();
        BigDecimal spread = null;
        BigDecimal mid = null;

        if (bid != null && ask != null) {
            spread = ask.subtract(bid);
            mid = bid.add(ask).divide(BigDecimal.TWO);
        }

        return new SpreadInfo(bid, ask, spread, mid);
    }

    /**
     * Get snapshot of current book state (for debugging).
     */
    public BookSnapshot getSnapshot() {
        List<LiquidityLevel> bids = new ArrayList<>();
        List<LiquidityLevel> asks = new ArrayList<>();

        for (PriceLevel level : bidSide.descendingMap().values()) {
            bids.add(new LiquidityLevel(level.price, level.getTotalQuantity()));
        }

        for (PriceLevel level : askSide.values()) {
            asks.add(new LiquidityLevel(level.price, level.getTotalQuantity()));
        }

        return new BookSnapshot(symbol, bids, asks);
    }

    /**
     * Price level containing orders at a specific price.
     */
    private static class PriceLevel {
        final BigDecimal price;
        final List<LimitOrder> orders = new ArrayList<>();

        PriceLevel(BigDecimal price) {
            this.price = price;
        }

        void addOrder(LimitOrder order) {
            orders.add(order);
        }

        boolean removeOrder(UUID orderId) {
            return orders.removeIf(o -> o.orderId.equals(orderId));
        }

        BigDecimal getTotalQuantity() {
            return orders.stream()
                    .map(o -> o.quantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        boolean isEmpty() {
            return orders.isEmpty();
        }
    }

    /**
     * Limit order record.
     */
    @Data
    @Builder
    public static class LimitOrder {
        private UUID orderId;
        private String side;  // BUY or SELL
        private BigDecimal quantity;
        private BigDecimal limitPrice;
        private Instant createdAt;

        public boolean isBuy() {
            return "BUY".equals(side);
        }
    }

    /**
     * Liquidity at a price level.
     */
    @Data
    public static class LiquidityLevel {
        private BigDecimal price;
        private BigDecimal quantity;

        public LiquidityLevel(BigDecimal price, BigDecimal quantity) {
            this.price = price;
            this.quantity = quantity;
        }
    }

    /**
     * Spread information (bid, ask, spread, midpoint).
     */
    @Data
    public static class SpreadInfo {
        private BigDecimal bestBid;
        private BigDecimal bestAsk;
        private BigDecimal spread;
        private BigDecimal midpoint;

        public SpreadInfo(BigDecimal bestBid, BigDecimal bestAsk, BigDecimal spread, BigDecimal midpoint) {
            this.bestBid = bestBid;
            this.bestAsk = bestAsk;
            this.spread = spread;
            this.midpoint = midpoint;
        }
    }

    /**
     * Book snapshot for debugging.
     */
    @Data
    public static class BookSnapshot {
        private String symbol;
        private List<LiquidityLevel> bids;
        private List<LiquidityLevel> asks;

        public BookSnapshot(String symbol, List<LiquidityLevel> bids, List<LiquidityLevel> asks) {
            this.symbol = symbol;
            this.bids = bids;
            this.asks = asks;
        }
    }
}
