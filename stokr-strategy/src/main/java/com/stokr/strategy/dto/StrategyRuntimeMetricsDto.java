package com.stokr.strategy.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StrategyRuntimeMetricsDto(
        UUID instanceId,
        UUID definitionId,
        String strategyKey,
        String symbol,
        String executionMode,
        String runtimeState,
        Instant startedAt,
        Instant stoppedAt,
        Long uptimeSeconds,
        long signalCount,
        Instant lastSignalAt,
        BigDecimal symbolUnrealizedPnl,
        String health
) {
}
