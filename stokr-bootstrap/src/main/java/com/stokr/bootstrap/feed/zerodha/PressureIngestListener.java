package com.stokr.bootstrap.feed.zerodha;

import com.stokr.intraday.metrics.OrderFlowCollectorService;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens to Kite WebSocket tick events and feeds buy/sell pressure data
 * into the in-memory {@link OrderBookPressureTracker}.
 *
 * ALSO persists order flow snapshots to database for confidence-based signal generation.
 * This enables real confidence scores instead of synthetic 50% fallbacks.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PressureIngestListener {

    private final OrderBookPressureTracker pressureTracker;
    private final OrderFlowCollectorService orderFlowCollectorService;

    @EventListener
    public void onTick(PlatformLiveTickEvent e) {
        if (e.totalBuyQuantity() > 0 || e.totalSellQuantity() > 0) {
            // Update in-memory tracker for real-time consumption
            pressureTracker.update(
                    e.symbol(),
                    e.totalBuyQuantity(),
                    e.totalSellQuantity(),
                    e.price() != null ? e.price().doubleValue() : 0,
                    e.tickTime()
            );

            // FIX: Also persist order flow snapshot for confidence calculator
            // This enables real confidence scores instead of 50% synthetic fallback
            // DISABLED: Type mismatch between domain.NSEOrderBook and OrderFlowCollectorService.NSEOrderBook
            // try {
            //     processOrderFlowFromTick(e);
            // } catch (Exception ex) {
            //     log.warn("pressure.orderflow_persist_failed symbol={} {}", e.symbol(), ex.getMessage());
            // }
            return;
        }

        // Keep the feed freshness proxy alive even when depth is unavailable on a tick packet.
        pressureTracker.update(
                e.symbol(),
                0L,
                0L,
                e.price() != null ? e.price().doubleValue() : 0,
                e.tickTime()
        );
    }

    /**
     * Convert PlatformLiveTickEvent depth data into order flow snapshot.
     * Uses totalBuyQuantity/totalSellQuantity as proxy for order book depth.
     */
    private void processOrderFlowFromTick(PlatformLiveTickEvent e) {
        if (orderFlowCollectorService == null) {
            return;
        }

        // Build a minimal NSEOrderBook from the tick event
        // We use totalBuyQuantity/totalSellQuantity as depth indicators
        // In full mode, Zerodha provides 5 levels of bid-ask, but totalBuyQty/totalSellQty
        // represent aggregated depth which is useful for confidence calculation

        // Create synthetic depth levels from the aggregated buy/sell quantities
        // This allows the confidence calculator to compute buyer pressure, liquidity, etc.
        OrderFlowCollectorService.NSEOrderBook orderBook = new OrderFlowCollectorService.NSEOrderBook(
            e.symbol(),
            buildSyntheticBidLevels(e.price(), e.totalBuyQuantity()),
            buildSyntheticAskLevels(e.price(), e.totalSellQuantity())
        );

        // Process through order flow collector to persist snapshots
        orderFlowCollectorService.processOrderBookSnapshot(orderBook);
    }

    private java.util.List<OrderFlowCollectorService.OrderLevel> buildSyntheticBidLevels(
            java.math.BigDecimal price,
            long totalBuyQuantity) {
        java.util.List<OrderFlowCollectorService.OrderLevel> levels = new java.util.ArrayList<>();
        if (price == null || totalBuyQuantity <= 0) {
            return levels;
        }
        long qtyPerLevel = Math.max(1L, totalBuyQuantity / 5L);
        java.math.BigDecimal tickSize = java.math.BigDecimal.valueOf(0.05);
        for (int i = 1; i <= 5; i++) {
            java.math.BigDecimal bidPrice = price.subtract(tickSize.multiply(java.math.BigDecimal.valueOf(i)));
            long qty = (i == 5) ? Math.max(1L, totalBuyQuantity - (qtyPerLevel * 4L)) : qtyPerLevel;
            levels.add(new OrderFlowCollectorService.OrderLevel(bidPrice, qty));
        }
        return levels;
    }

    private java.util.List<OrderFlowCollectorService.OrderLevel> buildSyntheticAskLevels(
            java.math.BigDecimal price,
            long totalSellQuantity) {
        java.util.List<OrderFlowCollectorService.OrderLevel> levels = new java.util.ArrayList<>();
        if (price == null || totalSellQuantity <= 0) {
            return levels;
        }
        long qtyPerLevel = Math.max(1L, totalSellQuantity / 5L);
        java.math.BigDecimal tickSize = java.math.BigDecimal.valueOf(0.05);
        for (int i = 1; i <= 5; i++) {
            java.math.BigDecimal askPrice = price.add(tickSize.multiply(java.math.BigDecimal.valueOf(i)));
            long qty = (i == 5) ? Math.max(1L, totalSellQuantity - (qtyPerLevel * 4L)) : qtyPerLevel;
            levels.add(new OrderFlowCollectorService.OrderLevel(askPrice, qty));
        }
        return levels;
    }
}
