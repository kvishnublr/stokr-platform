package com.stokr.strategy.capital;

import java.math.BigDecimal;

public record StrategyCapitalSummary(
        String strategyKey,
        BigDecimal allocatedCapital,
        BigDecimal utilizedCapital,
        BigDecimal availableCapital,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        int maxPositions,
        int openPositions,
        boolean liveEnabled,
        boolean emergencyStopEnabled,
        boolean enabled
) {
}
