package com.stokr.execution.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ExecutionAttemptTracker {

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();
    private final Duration ttl;

    public ExecutionAttemptTracker(
            @Value("${stokr.execution.guard.attempt-ttl-seconds:120}") long ttlSeconds
    ) {
        this.ttl = Duration.ofSeconds(Math.max(5L, ttlSeconds));
    }

    public boolean markIfFirst(ExecutionAttemptKey key, Instant now) {
        cleanup(now);
        return seen.putIfAbsent(key.toString(), now) == null;
    }

    private void cleanup(Instant now) {
        seen.entrySet().removeIf(e -> Duration.between(e.getValue(), now).compareTo(ttl) > 0);
    }
}

