package com.stokr.execution.exchange;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FIFO matching engine for limit and market orders.
 * Matches incoming orders against order book.
 */
@Slf4j
public class MatchingEngine {

    private final OrderBook orderBook;
    private final SlippageSimulator slippageSimulator;
    private final LatencySimulator latencySimulator;

    public MatchingEngine(OrderBook orderBook, SlippageSimulator slippageSimulator,
                         LatencySimulator latencySimulator) {
        this.orderBook = orderBook;
        this.slippageSimulator = slippageSimulator;
        this.latencySimulator = latencySimulator;
    }

    /**
     * Match a limit order against the order book.
     * Returns fills from matching against existing liquidity.
     */
    public MatchResult matchLimitOrder(UUID orderId, String side, BigDecimal quantity,
                                       BigDecimal limitPrice, Instant createdAt) {
        List<Fill> fills = new ArrayList<>();
        BigDecimal remainingQty = quantity;
        BigDecimal totalFilled = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;

        // Opposite side's order book
        boolean isBuy = "BUY".equals(side);
        List<OrderBook.LiquidityLevel> oppositeChain = isBuy
                ? orderBook.getLiquidity("SELL", 1000)
                : orderBook.getLiquidity("BUY", 1000);

        // Match against available liquidity
        for (OrderBook.LiquidityLevel level : oppositeChain) {
            if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            // Can we match at this price?
            if (isBuy && level.getPrice().compareTo(limitPrice) > 0) {
                // Buy order, but ask is above limit price - stop matching
                break;
            }
            if (!isBuy && level.getPrice().compareTo(limitPrice) < 0) {
                // Sell order, but bid is below limit price - stop matching
                break;
            }

            // Match quantity
            BigDecimal matchQty = remainingQty.min(level.getQuantity());
            BigDecimal fillPrice = level.getPrice();

            // Apply slippage (on buy side for BUY, on sell side for SELL)
            BigDecimal slippageBps = slippageSimulator.calculateSlippage(
                    matchQty, limitPrice, quantity
            );
            if (isBuy) {
                fillPrice = fillPrice.add(
                        fillPrice.multiply(slippageBps).divide(BigDecimal.valueOf(10_000), 8, RoundingMode.HALF_UP)
                );
            } else {
                fillPrice = fillPrice.subtract(
                        fillPrice.multiply(slippageBps).divide(BigDecimal.valueOf(10_000), 8, RoundingMode.HALF_UP)
                );
            }

            // Record fill
            long latencyMs = latencySimulator.getLatency();
            Fill fill = Fill.builder()
                    .orderId(orderId)
                    .fillId("fill-" + UUID.randomUUID())
                    .quantity(matchQty)
                    .price(fillPrice)
                    .side(side)
                    .timestamp(createdAt.plusMillis(latencyMs))
                    .latencyMs(latencyMs)
                    .slippageBps(slippageBps)
                    .build();

            fills.add(fill);
            totalFilled = totalFilled.add(matchQty);
            totalCost = totalCost.add(matchQty.multiply(fillPrice));
            remainingQty = remainingQty.subtract(matchQty);

            log.debug("matching.limit_fill symbol={} side={} qty={} price={} latencyMs={}",
                    orderBook.symbol, side, matchQty, fillPrice, latencyMs);
        }

        return MatchResult.builder()
                .orderId(orderId)
                .fills(fills)
                .totalFilled(totalFilled)
                .avgFillPrice(totalFilled.compareTo(BigDecimal.ZERO) > 0
                        ? totalCost.divide(totalFilled, 8, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO)
                .remaining(remainingQty)
                .isFilled(remainingQty.compareTo(BigDecimal.ZERO) <= 0)
                .build();
    }

    /**
     * Match a market order against the order book.
     * Market orders execute immediately at best available prices.
     */
    public MatchResult matchMarketOrder(UUID orderId, String side, BigDecimal quantity,
                                        Instant createdAt) {
        List<Fill> fills = new ArrayList<>();
        BigDecimal remainingQty = quantity;
        BigDecimal totalFilled = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;

        boolean isBuy = "BUY".equals(side);
        List<OrderBook.LiquidityLevel> oppositeChain = isBuy
                ? orderBook.getLiquidity("SELL", 1000)
                : orderBook.getLiquidity("BUY", 1000);

        // Market orders consume all available liquidity
        for (OrderBook.LiquidityLevel level : oppositeChain) {
            if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal matchQty = remainingQty.min(level.getQuantity());
            BigDecimal fillPrice = level.getPrice();

            // Apply slippage (market orders get slightly worse price)
            BigDecimal slippageBps = slippageSimulator.calculateMarketSlippage();
            if (isBuy) {
                fillPrice = fillPrice.add(
                        fillPrice.multiply(slippageBps).divide(BigDecimal.valueOf(10_000), 8, RoundingMode.HALF_UP)
                );
            } else {
                fillPrice = fillPrice.subtract(
                        fillPrice.multiply(slippageBps).divide(BigDecimal.valueOf(10_000), 8, RoundingMode.HALF_UP)
                );
            }

            long latencyMs = latencySimulator.getLatency();
            Fill fill = Fill.builder()
                    .orderId(orderId)
                    .fillId("fill-" + UUID.randomUUID())
                    .quantity(matchQty)
                    .price(fillPrice)
                    .side(side)
                    .timestamp(createdAt.plusMillis(latencyMs))
                    .latencyMs(latencyMs)
                    .slippageBps(slippageBps)
                    .build();

            fills.add(fill);
            totalFilled = totalFilled.add(matchQty);
            totalCost = totalCost.add(matchQty.multiply(fillPrice));
            remainingQty = remainingQty.subtract(matchQty);

            log.debug("matching.market_fill symbol={} side={} qty={} price={} latencyMs={}",
                    orderBook.symbol, side, matchQty, fillPrice, latencyMs);
        }

        return MatchResult.builder()
                .orderId(orderId)
                .fills(fills)
                .totalFilled(totalFilled)
                .avgFillPrice(totalFilled.compareTo(BigDecimal.ZERO) > 0
                        ? totalCost.divide(totalFilled, 8, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO)
                .remaining(remainingQty)
                .isFilled(remainingQty.compareTo(BigDecimal.ZERO) <= 0)
                .build();
    }

    /**
     * Result of order matching attempt.
     */
    @Data
    @Builder
    public static class MatchResult {
        private UUID orderId;
        private List<Fill> fills;
        private BigDecimal totalFilled;
        private BigDecimal avgFillPrice;
        private BigDecimal remaining;
        private boolean isFilled;
    }

    /**
     * Individual fill record from matching.
     */
    @Data
    @Builder
    public static class Fill {
        private UUID orderId;
        private String fillId;
        private BigDecimal quantity;
        private BigDecimal price;
        private String side;
        private Instant timestamp;
        private Long latencyMs;
        private BigDecimal slippageBps;
    }
}
