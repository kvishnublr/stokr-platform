package com.stokr.chartink;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents duplicate signals for the same symbol-direction within a cooldown window.
 * Chartink sends scanner hits every minute — we only want to trade once per setup.
 */
@Slf4j
@Service
public class SignalCooldownService {

    private final Map<String, Instant> lastSignalTime = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MINUTES = 15;

    private String key(String symbol, String side) {
        return (symbol + "_" + side).toUpperCase();
    }

    /**
     * Check if a signal is allowed (not in cooldown).
     */
    public boolean isAllowed(String symbol, String side) {
        String k = key(symbol, side);
        Instant last = lastSignalTime.get(k);
        if (last == null) return true;
        boolean allowed = Instant.now().isAfter(last.plusSeconds(COOLDOWN_MINUTES * 60));
        if (!allowed) {
            log.debug("Cooldown active for {} {} (last: {})", symbol, side, last);
        }
        return allowed;
    }

    /**
     * Record that a signal was processed.
     */
    public void record(String symbol, String side) {
        lastSignalTime.put(key(symbol, side), Instant.now());
        log.debug("Recorded signal for {} {}", symbol, side);
    }

    public void clear() {
        lastSignalTime.clear();
    }
}
