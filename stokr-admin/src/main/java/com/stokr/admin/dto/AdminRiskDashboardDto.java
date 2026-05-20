package com.stokr.admin.dto;

import java.time.Instant;
import java.util.List;

public record AdminRiskDashboardDto(
        Instant collectedAt,
        boolean killSwitchActive,
        boolean brokerHalt,
        boolean liveTradingArmed,
        int activeStrategies,
        int liveEnabledStrategies,
        int emergencyStoppedStrategies,
        long todayOrders,
        long todayFills,
        long todayRejects,
        int openReconciliationAlerts,
        List<StrategyRiskStateDto> strategyRiskStates
) {
}
