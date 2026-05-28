package com.stokr.strategy.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-symbol + per-strategy cooldown to prevent signal spam.
 *
 * Problem: VWAP was generating 3,937 signals today (75% of all signals)
 * with 84% expiring unused. Root cause: same symbol fires multiple times
 * within short windows without deduplication.
 *
 * Solution: Enforce cooldown window per symbol+strategy pair.
 * If a signal was generated for SYMBOL+VWAP_MEAN_REVERSION within cooldown,
 * skip the next signal for the same pair.
 *
 * This reduces noise from ~3,937 → ~400 signals while preserving signal quality.
 */
@Service
@Slf4j
public class SignalCooldownService {

    // Key: "SYMBOL:STRATEGY_KEY", Value: last signal Instant
    private final Map<String, Instant> lastSignalBySymbolStrategy = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.signal.cooldown-seconds:${stokr.signal.cooldown-seconds:900}}")
    private int cooldownSeconds;

    /**
     * Check if symbol + strategy combo should generate a new signal.
     *
     * @param symbol trading symbol (e.g., "RELIANCE")
     * @param strategyKey strategy identifier (e.g., "VWAP_MEAN_REVERSION")
     * @param now current instant
     * @return true if cooldown elapsed or first signal; false if in cooldown
     */
    public boolean shouldEmitSignal(String symbol, String strategyKey, Instant now) {
        return cooldownRemainingSeconds(symbol, strategyKey, now) <= 0;
    }

    public int cooldownRemainingSeconds(String symbol, String strategyKey, Instant now) {
        if (symbol == null || strategyKey == null || now == null) {
            return 0;
        }
        String key = symbol + ":" + strategyKey;
        Instant lastSignal = lastSignalBySymbolStrategy.get(key);
        if (lastSignal == null) {
            return 0;
        }
        long elapsed = now.getEpochSecond() - lastSignal.getEpochSecond();
        long remaining = cooldownSeconds - elapsed;
        return (int) Math.max(0, remaining);
    }

    /**
     * Records a successful emission after persist completes.
     */
    public void recordEmitted(String symbol, String strategyKey, Instant now) {
        if (symbol == null || strategyKey == null || now == null) {
            return;
        }
        lastSignalBySymbolStrategy.put(symbol + ":" + strategyKey, now);
    }

    /**
     * Reset cooldown for a symbol (e.g., trade closed, allow new entry).
     */
    public void reset(String symbol, String strategyKey) {
        if (symbol != null && strategyKey != null) {
            lastSignalBySymbolStrategy.remove(symbol + ":" + strategyKey);
        }
    }

    /**
     * Reset all cooldowns (testing/admin).
     */
    public void resetAll() {
        lastSignalBySymbolStrategy.clear();
        log.info("signal_cooldown.reset_all");
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(int seconds) {
        this.cooldownSeconds = Math.max(5, seconds);
        log.info("signal_cooldown.config_changed seconds={}", this.cooldownSeconds);
    }
}
