package com.stokr.oms.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OmsTradeRowDto(
        UUID id,
        UUID orderId,
        UUID executionId,
        UUID userId,
        String symbol,
        String strategyKey,
        String executionMode,
        UUID backtestRunId,
        BigDecimal quantity,
        BigDecimal price,
        Instant createdAt
) {
}
