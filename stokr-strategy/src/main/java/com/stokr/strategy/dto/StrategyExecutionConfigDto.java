package com.stokr.strategy.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StrategyExecutionConfigDto(
        UUID id,
        Instant createdAt,
        Instant updatedAt,
        UUID strategyId,
        String strategyKey,
        boolean enabled,
        String executionMode,
        boolean liveEnabled,
        boolean paperEnabled,
        boolean telegramEnabled,
        BigDecimal allocatedCapital,
        int maxPositions,
        BigDecimal maxTradeQuantity,
        boolean forceFixedQty,
        BigDecimal fixedQty,
        BigDecimal dailyLossLimit,
        int cooldownMinutes,
        boolean allowPyramiding,
        boolean emergencyStopEnabled,
        boolean autoDisableOnLoss,
        boolean liveConfirmationRequired
) {}
