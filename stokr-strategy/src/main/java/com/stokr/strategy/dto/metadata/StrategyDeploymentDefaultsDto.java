package com.stokr.strategy.dto.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Strategy-owned execution envelope defaults (symbol, timeframe, cost models). Shown read-only in the launcher;
 * the deployment API still receives these values for audit and deterministic replay.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StrategyDeploymentDefaultsDto(
        String symbol,
        String timeframe,
        String executionProfile,
        String feeModel,
        String slippageModel
) {
}
