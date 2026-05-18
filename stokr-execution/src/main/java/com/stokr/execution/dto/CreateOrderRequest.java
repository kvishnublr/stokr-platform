package com.stokr.execution.dto;

import com.stokr.oms.domain.ExecutionMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreateOrderRequest(
        @NotBlank String symbol,
        @NotBlank String side,
        @NotBlank String orderType,
        @NotNull @Positive BigDecimal quantity,
        BigDecimal limitPrice,
        ExecutionMode executionMode,
        String brokerVendor,
        String strategyKey,
        String idempotencyKey,
        UUID signalId,
        Instant signalGeneratedAt,
        BigDecimal signalReferencePrice,
        String timeframe,
        Boolean exitOrder,
        String guardMode
) {
    public CreateOrderRequest(
            String symbol,
            String side,
            String orderType,
            BigDecimal quantity,
            BigDecimal limitPrice,
            ExecutionMode executionMode,
            String brokerVendor,
            String strategyKey,
            String idempotencyKey
    ) {
        this(symbol, side, orderType, quantity, limitPrice, executionMode, brokerVendor, strategyKey, idempotencyKey,
                null, null, null, null, null, null);
    }
}
