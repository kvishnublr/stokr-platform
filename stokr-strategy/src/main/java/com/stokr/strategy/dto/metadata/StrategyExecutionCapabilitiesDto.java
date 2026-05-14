package com.stokr.strategy.dto.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StrategyExecutionCapabilitiesDto(
        boolean backtest,
        boolean paper,
        boolean live
) {
}
