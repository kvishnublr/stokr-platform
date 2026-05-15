package com.stokr.strategy.dto.metadata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Read-only marketing / confidence metrics for the strategy card (curated; not live PnL).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StrategyPreviewMetricsDto(
        Double avgMonthlyReturnPct,
        Double winRatePct,
        Double maxDrawdownPct,
        String riskLevel,
        Double avgTradesPerDay,
        String tradeFrequency
) {
}
