package com.stokr.execution.dto;

import com.stokr.oms.domain.ExecutionMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateOrderRequest(
        @NotBlank String symbol,
        @NotBlank String side,
        @NotBlank String orderType,
        @NotNull @Positive BigDecimal quantity,
        BigDecimal limitPrice,
        ExecutionMode executionMode,
        String brokerVendor,
        String strategyKey,
        String idempotencyKey
) {
}
