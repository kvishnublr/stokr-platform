package com.stokr.bootstrap.feed.zerodha;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by the Kite WebSocket listener for each market tick.
 *
 * @param symbol          resolved trading symbol (e.g. "RELIANCE")
 * @param tickTime        arrival instant
 * @param price           last traded price
 * @param instrumentToken Kite instrument token
 * @param lastTradedQty   quantity of the last trade (from quote/full mode; 0 if LTP-only)
 * @param volumeTraded    cumulative volume traded today (from quote/full mode; 0 if LTP-only)
 */
public record PlatformLiveTickEvent(
        String symbol,
        Instant tickTime,
        BigDecimal price,
        int instrumentToken,
        int lastTradedQty,
        long volumeTraded
) {
    /** Backwards-compatible constructor for LTP-only mode (no volume data) */
    public PlatformLiveTickEvent(String symbol, Instant tickTime, BigDecimal price, int instrumentToken) {
        this(symbol, tickTime, price, instrumentToken, 0, 0L);
    }
}
