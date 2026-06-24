package com.stokr.chartink;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents duplicate signals for the same (strategy, symbol, side) within a cooldown window.
 * Chartink sends scanner hits every minute — we only want to trade once per setup per strategy.
 */
@Slf4j
@Service
public class SignalCooldownService {

    private final Map<String, Instant> lastSignalTime = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MINUTES = 15;

    private String key(String scannerName, String symbol, String side) {
        String scanner = scannerName != null ? scannerName.toUpperCase() : "UNKNOWN";
        return ("SCAN_" + scanner + "_" + symbol + "_" + side).toUpperCase();
    }

    private String key(Long strategyId, String symbol, String side) {
        Long id = strategyId != null ? strategyId : 0L;
        return ("STRAT_" + id + "_" + symbol + "_" + side).toUpperCase();
    }

    public boolean isAllowed(String scannerName, String symbol, String side) {
        String k = key(scannerName, symbol, side);
        return isAllowedByKey(k, scannerName, symbol, side);
    }

    public boolean isAllowed(Long strategyId, String symbol, String side) {
        String k = key(strategyId, symbol, side);
        return isAllowedByKey(k, String.valueOf(strategyId), symbol, side);
    }

    private boolean isAllowedByKey(String k, String label, String symbol, String side) {
        Instant last = lastSignalTime.get(k);
        if (last == null) return true;
        boolean allowed = Instant.now().isAfter(last.plusSeconds(COOLDOWN_MINUTES * 60));
        if (!allowed) {
            log.debug("Cooldown active for {} {} {} (last: {})", label, symbol, side, last);
        }
        return allowed;
    }

    public void record(String scannerName, String symbol, String side) {
        lastSignalTime.put(key(scannerName, symbol, side), Instant.now());
    }

    public void record(Long strategyId, String symbol, String side) {
        lastSignalTime.put(key(strategyId, symbol, side), Instant.now());
    }

    public void clear() {
        lastSignalTime.clear();
    }
}
