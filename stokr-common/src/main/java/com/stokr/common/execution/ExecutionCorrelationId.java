package com.stokr.common.execution;

import java.util.UUID;

/**
 * Stable correlation handle for signal → OMS → execution tracing (string form fits OMS columns).
 */
public record ExecutionCorrelationId(String value) {

    public ExecutionCorrelationId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("correlation id required");
        }
    }

    public static ExecutionCorrelationId of(String raw) {
        return new ExecutionCorrelationId(raw.trim());
    }

    public static ExecutionCorrelationId fresh() {
        return new ExecutionCorrelationId(UUID.randomUUID().toString());
    }
}
