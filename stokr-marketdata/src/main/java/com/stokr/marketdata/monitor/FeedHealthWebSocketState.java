package com.stokr.marketdata.monitor;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class FeedHealthWebSocketState {

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicReference<Instant> lastConnectedAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastDisconnectedAt = new AtomicReference<>();
    private final AtomicReference<String> lastDisconnectReason = new AtomicReference<>();
    private final AtomicReference<Instant> lastTickAt = new AtomicReference<>();

    public void markConnected() {
        connected.set(true);
        lastConnectedAt.set(Instant.now());
    }

    public void recordTick(Instant tickTime) {
        if (tickTime != null) {
            lastTickAt.set(tickTime);
        }
    }

    public void recordTickNow() {
        lastTickAt.set(Instant.now());
    }

    public void markDisconnected(String reason) {
        connected.set(false);
        lastDisconnectedAt.set(Instant.now());
        lastDisconnectReason.set(reason);
    }

    public void incrementReconnectAttempt() {
        reconnectAttempts.incrementAndGet();
    }

    public void resetReconnectAttempts() {
        reconnectAttempts.set(0);
    }

    public boolean isConnected() {
        return connected.get();
    }

    public int reconnectAttempts() {
        return reconnectAttempts.get();
    }

    public Instant lastConnectedAt() {
        return lastConnectedAt.get();
    }

    public Instant lastDisconnectedAt() {
        return lastDisconnectedAt.get();
    }

    public String lastDisconnectReason() {
        return lastDisconnectReason.get();
    }

    public Instant lastTickAt() {
        return lastTickAt.get();
    }

    public long tickGapSeconds(Instant now) {
        Instant tick = lastTickAt.get();
        if (tick == null) {
            return Long.MAX_VALUE / 2;
        }
        return Math.max(0, java.time.Duration.between(tick, now).getSeconds());
    }
}
