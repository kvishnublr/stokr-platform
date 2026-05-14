package com.stokr.common.pipeline.messages;

import java.time.Instant;
import java.util.UUID;

public record ExecutionDispatchMessage(
        UUID orderId,
        UUID userId,
        UUID signalId,
        String brokerVendor,
        int attempt,
        UUID backtestRunId,
        String executionMode,
        /** Deterministic seed / key for fill simulation (null = derive from order id only). */
        Long fillDeterminismKey,
        /** Wall-clock-free anchor for simulated timestamps (null = use order created time). */
        Instant deterministicAnchor
) {
    public ExecutionDispatchMessage(
            UUID orderId,
            UUID userId,
            UUID signalId,
            String brokerVendor,
            int attempt,
            UUID backtestRunId,
            String executionMode
    ) {
        this(orderId, userId, signalId, brokerVendor, attempt, backtestRunId, executionMode, null, null);
    }
}
