package com.stokr.backtest.web.dto;

import com.stokr.backtest.domain.BacktestStatus;

import java.time.Instant;
import java.util.UUID;

public record BacktestRunSummaryDto(
        UUID id,
        String strategyKey,
        String symbol,
        BacktestStatus status,
        long seed,
        String timeframe,
        Instant rangeStart,
        Instant rangeEnd,
        Instant createdAt,
        String replayHashPreview
) {
}
