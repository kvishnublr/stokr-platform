package com.stokr.strategy.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

public record StrategyExecutionConfigRequest(
        UUID strategyId,

        @NotBlank
        String strategyKey,

        boolean enabled,

        @NotNull
        @Pattern(regexp = "PAPER|LIVE|BOTH", message = "executionMode must be PAPER, LIVE, or BOTH")
        String executionMode,

        boolean liveEnabled,
        boolean paperEnabled,
        boolean telegramEnabled,

        BigDecimal allocatedCapital,

        @Min(0)
        int maxPositions,

        BigDecimal maxTradeQuantity,

        boolean forceFixedQty,

        @NotNull
        BigDecimal fixedQty,

        BigDecimal dailyLossLimit,

        @Min(0)
        int cooldownMinutes,

        boolean allowPyramiding,
        boolean emergencyStopEnabled,
        boolean autoDisableOnLoss,
        boolean liveConfirmationRequired
) {}
