package com.stokr.execution.guard;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ExecutionGuardRealtimeEvent(
        String eventType,
        Instant emittedAt,
        UUID userId,
        String severity,
        String outcome,
        String symbol,
        String strategyKey,
        Map<String, Object> payload
) {
}

