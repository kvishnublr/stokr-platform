package com.stokr.backtest.web.dto;

import com.stokr.backtest.domain.BacktestJobStatus;

import java.time.Instant;
import java.util.UUID;

public record BacktestJobStatusDto(
        UUID id,
        BacktestJobStatus status,
        int progress,
        int totalBars,
        int processedBars,
        UUID runId,
        String message,
        boolean cancelled,
        Integer metadataSchemaVersion,
        Long strategyDefinitionVersion,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Long etaSecondsRemaining
) {
}
