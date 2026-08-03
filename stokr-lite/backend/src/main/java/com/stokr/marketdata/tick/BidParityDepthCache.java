package com.stokr.marketdata.tick;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory best bid/ask cache for bid-parity monitoring.
 * Populated from tick stream when depth fields are present; otherwise LTP-only.
 */
@Component
public class BidParityDepthCache {

    private final ConcurrentHashMap<String, DepthTick> cache = new ConcurrentHashMap<>();

    public void onTick(TickData tick) {
        if (tick == null || tick.getSymbol() == null) return;
        DepthTick dt = cache.computeIfAbsent(tick.getSymbol(), s -> new DepthTick());
        dt.symbol = tick.getSymbol();
        if (tick.getLtp() != null) dt.ltp = tick.getLtp().doubleValue();
        // TickData currently persists aggregate buy/sell qty, not price levels.
        // Keep prior bid/ask if present; do not zero them on every LTP tick.
        if (tick.getBuyQuantity() > 0) dt.bidQty = tick.getBuyQuantity();
        if (tick.getSellQuantity() > 0) dt.askQty = tick.getSellQuantity();
        dt.timestamp = tick.getReceivedTs();
        cache.put(tick.getSymbol(), dt);
    }

    public void onDepth(String symbol, double bidPrice, long bidQty, double askPrice, long askQty, double ltp) {
        DepthTick dt = cache.computeIfAbsent(symbol, s -> new DepthTick());
        dt.symbol = symbol;
        dt.bidPrice = bidPrice;
        dt.bidQty = bidQty;
        dt.askPrice = askPrice;
        dt.askQty = askQty;
        if (ltp > 0) dt.ltp = ltp;
        dt.timestamp = java.time.LocalDateTime.now();
        cache.put(symbol, dt);
    }

    public DepthTick get(String symbol) {
        return cache.get(symbol);
    }

    public Map<String, DepthTick> getAll() {
        return new LinkedHashMap<>(cache);
    }

    public static class DepthTick {
        public String symbol;
        public double ltp;
        public double bidPrice;
        public long bidQty;
        public double askPrice;
        public long askQty;
        public java.time.LocalDateTime timestamp;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", symbol);
            m.put("ltp", ltp);
            m.put("bidPrice", bidPrice);
            m.put("bidQty", bidQty);
            m.put("askPrice", askPrice);
            m.put("askQty", askQty);
            m.put("hasDepth", bidPrice > 0 && askPrice > 0);
            m.put("timestamp", timestamp != null ? timestamp.toString() : null);
            return m;
        }
    }
}
