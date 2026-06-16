package com.stokr.execution.guard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SignalExecutionMetadata(
        UUID signalId,
        Instant signalGeneratedAt,
        BigDecimal signalReferencePrice,
        String timeframe
) {
}

