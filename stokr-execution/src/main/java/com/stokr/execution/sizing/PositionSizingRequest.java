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
        boolean testTrade,
        boolean simulationHarness) {

    public PositionSizingRequest(
            String strategyKey,
            UUID userId,
            UUID signalId,
            String symbol,
            BigDecimal suggestedQty,
            BigDecimal marketPrice,
            ExecutionMode executionMode,
            boolean testTrade) {
        this(strategyKey, userId, signalId, symbol, suggestedQty, marketPrice, executionMode, testTrade, false);
    }

    public static PositionSizingRequest ofLegacy(
            String strategyKey,
            UUID userId,
            BigDecimal suggestedQty,
            BigDecimal marketPrice,
            boolean testTrade) {
        return new PositionSizingRequest(
                strategyKey, userId, null, null, suggestedQty, marketPrice, ExecutionMode.PAPER, testTrade, false);
    }
}
