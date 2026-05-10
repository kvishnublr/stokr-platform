package com.stokr.backtest.web.dto;

import java.time.Instant;

public record BacktestJournalEntryDto(
        long sequenceNum,
        String eventType,
        String payloadJson,
        Instant createdAt,
        String chainHash,
        String correlationId,
        String strategyKey
) {
}
