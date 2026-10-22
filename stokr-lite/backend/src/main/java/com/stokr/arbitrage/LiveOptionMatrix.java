package com.stokr.arbitrage;

import com.stokr.marketdata.tick.KiteTickWebSocketClient;
import com.stokr.marketdata.tick.TickData;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance, zero-latency in-memory option quote matrix fed directly
 * by Kite WebSocket streaming ticks. Eliminates REST API rate limits (3 req/sec)
 * and provides sub-millisecond RAM lookups for all spread scanners.
 */
@Slf4j
@Component
public class LiveOptionMatrix {

    private final KiteTickWebSocketClient tickClient;
    private final ConcurrentHashMap<String, OptionChainService.OptionQuote> liveMatrix = new ConcurrentHashMap<>();

    public LiveOptionMatrix(KiteTickWebSocketClient tickClient) {
        this.tickClient = tickClient;
    }

    @PostConstruct
    public void init() {
        tickClient.addTickListener(this::onTick);
        log.info("LiveOptionMatrix initialized and subscribed to WebSocket tick feed");
    }

    public void onTick(TickData tick) {
        if (tick == null || tick.getSymbol() == null) return;
        String symbol = tick.getSymbol();
        double ltp = (tick.getLtp() != null) ? tick.getLtp().doubleValue() : 0.0;
        if (ltp <= 0) return;

        double bid = (tick.getBestBid() != null && tick.getBestBid().doubleValue() > 0)
                ? tick.getBestBid().doubleValue() : ltp;
        double ask = (tick.getBestAsk() != null && tick.getBestAsk().doubleValue() > 0)
                ? tick.getBestAsk().doubleValue() : ltp;
        int bidQty = tick.getBestBidQty();
        int askQty = tick.getBestAskQty();
        long volume = tick.getVolume();
        long oi = tick.getOpenInterest();
        long now = System.currentTimeMillis();

        OptionChainService.OptionQuote q = new OptionChainService.OptionQuote();
        q.symbol = symbol;
        q.lastPrice = ltp;
        q.bid = bid;
        q.ask = ask;
        q.bidQty = bidQty;
        q.askQty = askQty;
        q.volume = (int) Math.min(Integer.MAX_VALUE, volume);
        q.openInterest = (int) Math.min(Integer.MAX_VALUE, oi);
        q.fetchedAtMs = now;

        liveMatrix.put(symbol, q);
        if (!symbol.startsWith("NFO:") && !symbol.startsWith("NSE:")) {
            liveMatrix.put("NFO:" + symbol, q);
        }
    }

    /**
     * Look up a quote from the live matrix. Returns null if missing or older than maxAgeMs.
     */
    public OptionChainService.OptionQuote getQuote(String symbol, long maxAgeMs) {
        if (symbol == null) return null;
        OptionChainService.OptionQuote q = liveMatrix.get(symbol);
        if (q == null && !symbol.startsWith("NFO:") && !symbol.startsWith("NSE:")) {
            q = liveMatrix.get("NFO:" + symbol);
        }
        if (q == null && symbol.startsWith("NFO:")) {
            q = liveMatrix.get(symbol.substring(4));
        }
        if (q == null) return null;

        if (maxAgeMs > 0 && (System.currentTimeMillis() - q.fetchedAtMs) > maxAgeMs) {
            return null; // Quote is stale
        }
        return q;
    }

    public Map<String, OptionChainService.OptionQuote> getQuotes(List<String> symbols, long maxAgeMs) {
        if (symbols == null || symbols.isEmpty()) return Collections.emptyMap();
        Map<String, OptionChainService.OptionQuote> result = new LinkedHashMap<>();
        for (String s : symbols) {
            OptionChainService.OptionQuote q = getQuote(s, maxAgeMs);
            if (q != null) {
                result.put(s, q);
            }
        }
        return result;
    }

    public int getTrackedSymbolCount() {
        return liveMatrix.size();
    }
}