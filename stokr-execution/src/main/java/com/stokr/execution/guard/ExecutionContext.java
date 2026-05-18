package com.stokr.execution.guard;

import com.stokr.execution.dto.CreateOrderRequest;
import com.stokr.oms.domain.ExecutionMode;

import java.time.Instant;
import java.util.UUID;

public record ExecutionContext(
        UUID userId,
        Instant now,
        String strategyKey,
        String symbol,
        String side,
        ExecutionMode executionMode,
        ExecutionGuardMode guardMode,
        CreateOrderRequest request,
        SignalExecutionMetadata signalMeta
) {
}

