package com.stokr.marketdata.chartink;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ChartinkAlertStore {

    private final ConcurrentHashMap<String, AlertEntry> alerts = new ConcurrentHashMap<>();

    public void put(String symbol, String scanName, double triggerPrice, Instant timestamp) {
        alerts.put(symbol, new AlertEntry(scanName, triggerPrice, timestamp));
    }

    public boolean hasRecentAlert(String symbol, Duration maxAge) {
        AlertEntry entry = alerts.get(symbol);
        if (entry == null) return false;
        if (Duration.between(entry.timestamp(), Instant.now()).abs().compareTo(maxAge) > 0) {
            alerts.remove(symbol, entry);
            return false;
        }
        return true;
    }

    public void evictExpired(Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        alerts.values().removeIf(e -> e.timestamp().isBefore(cutoff));
    }

    public List<String> recentSymbols(Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        return alerts.entrySet().stream()
                .filter(e -> e.getValue().timestamp().isAfter(cutoff))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    public int size() { return alerts.size(); }

    private record AlertEntry(String scanName, double triggerPrice, Instant timestamp) {}
}
