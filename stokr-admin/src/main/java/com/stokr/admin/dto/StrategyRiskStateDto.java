package com.stokr.admin.dto;

import java.math.BigDecimal;

public record StrategyRiskStateDto(
        String strategyKey,
        boolean enabled,
        boolean liveEnabled,
        boolean emergencyStopEnabled,
        boolean autoDisableOnLoss,
        BigDecimal dailyLossLimit,
        BigDecimal todayPnl,
        int maxPositions,
        int openPositions,
        BigDecimal allocatedCapital,
        BigDecimal utilizedCapital,
        String executionMode
) {
}
