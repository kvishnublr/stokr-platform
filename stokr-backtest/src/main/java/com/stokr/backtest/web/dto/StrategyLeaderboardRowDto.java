package com.stokr.backtest.web.dto;

import java.math.BigDecimal;

public record StrategyLeaderboardRowDto(
        String strategyKey,
        BigDecimal avgSharpeRatio,
        BigDecimal avgWinRate,
        BigDecimal avgMaxDrawdown,
        Long sampleRuns
) {
}
