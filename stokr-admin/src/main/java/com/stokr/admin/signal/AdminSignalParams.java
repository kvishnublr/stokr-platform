package com.stokr.admin.signal;

import java.time.Instant;
import java.util.UUID;

public record AdminSignalParams(
        String strategyName,
        String symbol,
        String signalType,
        String pipeline,
        UUID userId,
        Instant from,
        Instant to,
        String outcomeStatus
) {
    public AdminSignalParams(String strategyName, String symbol, String signalType,
                             String pipeline, UUID userId, Instant from, Instant to) {
        this(strategyName, symbol, signalType, pipeline, userId, from, to, null);
    }
}
