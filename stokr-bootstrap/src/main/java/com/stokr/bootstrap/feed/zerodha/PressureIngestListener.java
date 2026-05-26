package com.stokr.bootstrap.feed.zerodha;

import com.stokr.marketdata.service.OrderBookPressureTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens to Kite WebSocket tick events and feeds buy/sell pressure data
 * into the in-memory {@link OrderBookPressureTracker}.
 *
 * No DB overhead — purely in-memory for real-time strategy consumption.
 */
@Component
@RequiredArgsConstructor
public class PressureIngestListener {

    private final OrderBookPressureTracker pressureTracker;

    @EventListener
    public void onTick(PlatformLiveTickEvent e) {
        if (e.totalBuyQuantity() > 0 || e.totalSellQuantity() > 0) {
            pressureTracker.update(
                    e.symbol(),
                    e.totalBuyQuantity(),
                    e.totalSellQuantity(),
                    e.price() != null ? e.price().doubleValue() : 0,
                    e.tickTime()
            );
        }
    }
}
