package com.stokr.common.pipeline.messages;

import java.util.UUID;

public record ExecutionDispatchMessage(
        UUID orderId,
        UUID userId,
        UUID signalId,
        String brokerVendor,
        int attempt,
        UUID backtestRunId,
        String executionMode
) {
}
