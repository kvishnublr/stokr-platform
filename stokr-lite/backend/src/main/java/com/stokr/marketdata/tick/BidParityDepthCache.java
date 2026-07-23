package com.stokr.marketdata.tick;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BidParityDepthCache {

    private final ConcurrentHashMap<String, DepthTick> cache = new ConcurrentHashMap<>();

    public void onTick(TickData tick) {
        if (tick.getBidPrice() <= 0 && tick.getAskPrice() <= 0) return;
        DepthTick dt = new DepthTick();
        dt.symbol = tick.getSymbol();
        dt.ltp = tick.getLtp().doubleValue();
        dt.bidPrice = tick.getBidPrice();
        dt.bidQty = tick.getBidQty();
        dt.askPrice = tick.getAskPrice();
        dt.askQty = tick.getAskQty();
        dt.timestamp = tick.getReceivedTs();
        cache.put(tick.getSymbol(), dt);
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
            m.put("timestamp", timestamp != null ? timestamp.toString() : null);
            return m;
        }
    }
}
