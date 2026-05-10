package com.stokr.oms.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OmsExecutionRowDto(
        UUID id,
        UUID orderId,
        UUID userId,
        String symbol,
        String strategyKey,
        String executionMode,
        String orderState,
        UUID backtestRunId,
        String brokerExecutionId,
        Long executionSequence,
        BigDecimal filledQty,
        BigDecimal avgPrice,
        String executionKind,
        Instant fillTime,
        Instant executionTimestamp,
        Long latencyMs,
        BigDecimal slippageBps,
        BigDecimal spreadBps,
        BigDecimal referencePrice,
        String replaySource,
        UUID replayRunId,
        Instant createdAt
) {
}
