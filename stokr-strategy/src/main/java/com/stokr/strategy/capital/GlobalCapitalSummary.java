package com.stokr.strategy.capital;

import java.math.BigDecimal;
import java.util.List;

public record GlobalCapitalSummary(
        BigDecimal totalAllocatedCapital,
        BigDecimal totalUtilizedCapital,
        BigDecimal totalAvailableCapital,
        BigDecimal totalRealizedPnl,
        BigDecimal totalUnrealizedPnl,
        int activeStrategies,
        int liveEnabledStrategies,
        int emergencyStoppedStrategies,
        List<StrategyCapitalSummary> strategies
) {
}
