package com.stokr.strategy.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UserStrategyInstanceDto(
        UUID id,
        UUID definitionId,
        String strategyKey,
        String strategyDisplayName,
        String symbol,
        boolean subscriptionEnabled,
        String executionMode,
        String runtimeState,
        BigDecimal allocationAmount,
        BigDecimal riskMultiplier,
        BigDecimal maxDailyLoss,
        Instant startedAt,
        Instant stoppedAt
) {
}
