package com.stokr.oms.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OmsOrderSummaryDto(
        UUID id,
        UUID userId,
        String correlationId,
        UUID signalId,
        String strategyKey,
        String executionMode,
        String symbol,
        String side,
        String orderType,
        BigDecimal quantity,
        BigDecimal limitPrice,
        UUID backtestRunId,
        String state,
        String brokerVendor,
        String rejectReason,
        Instant createdAt
) {
}
