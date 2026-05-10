package com.stokr.common.pipeline.messages;

import java.util.UUID;

public record SignalPersistedMessage(
        UUID signalId,
        UUID userId,
        String correlationId,
        UUID backtestRunId,
        String executionMode
) {
}
