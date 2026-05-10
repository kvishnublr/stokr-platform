package com.stokr.marketdata.runtime;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryCandleManager {

    private final Map<String, List<BigDecimal>> closesBySymbol = new ConcurrentHashMap<>();

    public void appendClose(String symbol, Instant ts, BigDecimal close) {
        closesBySymbol
                .computeIfAbsent(symbol.toUpperCase(), s -> new ArrayList<>())
                .add(close);
    }

    public List<BigDecimal> lastCloses(String symbol, int max) {
        List<BigDecimal> list = closesBySymbol.getOrDefault(symbol.toUpperCase(), Collections.emptyList());
        int from = Math.max(0, list.size() - max);
        return List.copyOf(list.subList(from, list.size()));
    }
}
