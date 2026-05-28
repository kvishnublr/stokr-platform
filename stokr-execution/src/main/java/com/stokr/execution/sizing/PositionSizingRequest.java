package com.stokr.execution.sizing;

import com.stokr.oms.domain.ExecutionMode;

import java.math.BigDecimal;
import java.util.UUID;

public record PositionSizingRequest(
        String strategyKey,
        UUID userId,
        UUID signalId,
        String symbol,
        BigDecimal suggestedQty,
        BigDecimal marketPrice,
        ExecutionMode executionMode,
        boolean testTrade) {

    public static PositionSizingRequest ofLegacy(
            String strategyKey,
            UUID userId,
            BigDecimal suggestedQty,
            BigDecimal marketPrice,
            boolean testTrade) {
        return new PositionSizingRequest(
                strategyKey, userId, null, null, suggestedQty, marketPrice, ExecutionMode.PAPER, testTrade);
    }
}
