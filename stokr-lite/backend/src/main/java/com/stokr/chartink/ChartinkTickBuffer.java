package com.stokr.chartink;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ChartinkTickBuffer {

    private static final int MAX_CANDLES = 50;
    private final Map<String, LinkedHashMap<Long, ChartinkPayload>> recentBySymbol = new ConcurrentHashMap<>();

    public void add(ChartinkPayload p) {
        if (p.symbol() == null || p.timestamp() == null) return;
        long epochMinute = p.timestamp().getEpochSecond() / 60;

        LinkedHashMap<Long, ChartinkPayload> perSymbol = recentBySymbol.computeIfAbsent(
                p.symbol(), k -> new LinkedHashMap<>() {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, ChartinkPayload> eldest) {
                        return size() > MAX_CANDLES;
                    }
                });

        synchronized (perSymbol) {
            perSymbol.put(epochMinute, p);
        }
    }

    public List<Candle> toCandles(String symbol) {
        LinkedHashMap<Long, ChartinkPayload> perSymbol = recentBySymbol.get(symbol);
        if (perSymbol == null || perSymbol.isEmpty()) return List.of();
        synchronized (perSymbol) {
            return perSymbol.values().stream()
                    .map(this::toCandle)
                    .toList();
        }
    }

    private Candle toCandle(ChartinkPayload p) {
        LocalDateTime ts = p.timestamp() != null
                ? LocalDateTime.ofInstant(p.timestamp(), ZoneId.systemDefault())
                : LocalDateTime.now();
        return new Candle(
                p.symbol(),
                ts,
                p.open() != null ? p.open() : BigDecimal.ZERO,
                p.high() != null ? p.high() : BigDecimal.ZERO,
                p.low() != null ? p.low() : BigDecimal.ZERO,
                p.close() != null ? p.close() : p.ltp(),
                p.volume() != null ? p.volume() : 0L
        );
    }
}
