package com.stokr.backtest.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record ReplayRequest(
        @NotBlank String symbol,
        @NotNull Instant start,
        @NotNull Instant end,
        UUID userId,
        long seed,
        String strategyKey,
        String timeframe,
        String executionProfile,
        String feeModel,
        String slippageModel
) {
}
