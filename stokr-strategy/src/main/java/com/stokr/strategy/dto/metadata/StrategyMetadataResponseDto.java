package com.stokr.strategy.dto.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StrategyMetadataResponseDto(
        int schemaVersion,
        String strategyKey,
        String displayName,
        String description,
        String category,
        List<String> supportedMarkets,
        List<String> requiredIndicators,
        StrategyExecutionCapabilitiesDto executionCapabilities,
        List<StrategyParameterFieldDto> parameters,
        /** Allowed values for unified execution envelope (null = skip root-level enum validation). */
        List<String> allowedTimeframes,
        List<String> allowedExecutionModes,
        List<String> allowedFeeModels,
        List<String> allowedSlippageModels,
        List<String> allowedExecutionProfiles
) {
}
