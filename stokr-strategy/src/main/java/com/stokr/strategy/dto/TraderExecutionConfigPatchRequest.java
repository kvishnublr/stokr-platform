package com.stokr.strategy.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Fields a trader is allowed to edit on their own execution config. */
public record TraderExecutionConfigPatchRequest(
        boolean enabled,
        boolean telegramEnabled,
        boolean forceFixedQty,
        @NotNull BigDecimal fixedQty,
        @Min(0) int maxPositions,
        BigDecimal dailyLossLimit,
        @Min(0) int cooldownMinutes,
        boolean allowPyramiding,
        boolean emergencyStopEnabled
) {}
