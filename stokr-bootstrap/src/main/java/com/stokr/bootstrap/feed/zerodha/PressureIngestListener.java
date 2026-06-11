package com.stokr.bootstrap.feed.zerodha;

import com.stokr.intraday.metrics.OrderFlowCollectorService;
import com.stokr.intraday.metrics.domain.NSEOrderBook;
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
            try {
                // Create a minimal NSE order book from the tick event
                // The order flow collector will extract buy/sell pressure from this
                processOrderFlowFromTick(e);
            } catch (Exception ex) {
                log.warn("pressure.orderflow_persist_failed symbol={} {}", e.symbol(), ex.getMessage());
                // Don't fail the tick ingestion if order flow persistence fails
            }
        }
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
        NSEOrderBook orderBook = new NSEOrderBook(
            e.symbol(),
            e.price(),
            e.totalBuyQuantity(),  // Aggregate buy quantity (depth)
            e.totalSellQuantity()  // Aggregate sell quantity (depth)
        );

        // Process through order flow collector to persist snapshots
        orderFlowCollectorService.processOrderBookSnapshot(orderBook);
    }
}
