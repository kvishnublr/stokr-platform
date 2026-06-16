package com.stokr.strategy.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One asset-class bucket (CASH | F&O | CURRENCY | MCX) in the trader-facing
 * capital allocation manager. On read, {@code memberStrategyKeys} lists the
 * strategies this bucket fans out to. On write, that field is ignored ??? the
 * server re-resolves membership from the catalog.
 */
public record AssetClassAllocationDto(
        String assetClass,
        BigDecimal allocatedCapital,
        int maxConcurrentPositions,
        BigDecimal perTradeLimitAmount,
        boolean enabled,
        BigDecimal dailyLossLimit,
        boolean dailyLossLimitEnabled,
        List<String> memberStrategyKeys
) {}
