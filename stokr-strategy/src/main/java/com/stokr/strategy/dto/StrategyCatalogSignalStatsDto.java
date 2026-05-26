package com.stokr.strategy.dto;

import java.time.Instant;

public record StrategyCatalogSignalStatsDto(
        String strategyKey,
        long signalsToday,
        Instant lastSignalAt
) {
}
