package com.stokr.strategy.telemetry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional INFO-level gate telemetry for catalog scan diagnostics (prod troubleshooting).
 * Enable with {@code stokr.strategy.gate-telemetry.enabled=true}.
 */
@Component
@Slf4j
public class StrategyGateTelemetry {

    @Value("${stokr.strategy.gate-telemetry.enabled:false}")
    private boolean enabled;

    @Value("${stokr.strategy.gate-telemetry.min-interval-seconds:60}")
    private int minIntervalSeconds;

    private final ConcurrentHashMap<String, Instant> lastLogged = new ConcurrentHashMap<>();

    public boolean enabled() {
        return enabled;
    }

    public void infoThrottled(String strategyKey, String gate, String message, Object... args) {
        if (!enabled) {
            return;
        }
        String throttleKey = strategyKey + ":" + gate;
        Instant now = Instant.now();
        Instant prev = lastLogged.get(throttleKey);
        if (prev != null && Duration.between(prev, now).getSeconds() < minIntervalSeconds) {
            return;
        }
        lastLogged.put(throttleKey, now);
        String formatted = String.format(message, args);
        log.info("strategy.gate_hold strategy={} gate={} {}", strategyKey, gate, formatted);
    }

    public void infoNearMiss(String strategyKey, String symbol, String gate, String message, Object... args) {
        if (!enabled) {
            return;
        }
        String formatted = String.format(message, args);
        log.info("strategy.gate_near_miss strategy={} symbol={} gate={} {}", strategyKey, symbol, gate, formatted);
    }
}
