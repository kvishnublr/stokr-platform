package com.stokr.backtest.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Canonical synchronous backtest / replay envelope (PR-2). Strategy-specific semantics live in {@code strategyParameters}.
 */
public record ExecutionRequestDto(
        @NotBlank @Size(max = 128) String strategyKey,
        @NotBlank @Size(max = 64) String symbol,
        @NotBlank @Size(max = 16) String timeframe,
        @NotBlank @Pattern(regexp = "(?i)BACKTEST|PAPER|LIVE") String executionMode,
        @NotBlank @Size(max = 64) String executionProfile,
        @NotNull @DecimalMin(value = "1.0", inclusive = true) @Digits(integer = 14, fraction = 8) BigDecimal capital,
        @NotBlank @Size(max = 64) String feeModel,
        @NotBlank @Size(max = 64) String slippageModel,
        Long seed,
        @NotNull @Valid TimeRangeDto range,
        @NotNull Map<String, Object> strategyParameters
) {
    public ExecutionRequestDto withResolvedSeed(long resolvedSeed) {
        return new ExecutionRequestDto(
                strategyKey,
                symbol,
                timeframe,
                executionMode,
                executionProfile,
                capital,
                feeModel,
                slippageModel,
                resolvedSeed,
                range,
                strategyParameters
        );
    }
}
