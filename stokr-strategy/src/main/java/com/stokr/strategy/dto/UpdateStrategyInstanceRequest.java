package com.stokr.strategy.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Partial update for a user's strategy instance (symbol, risk, allocation, execution mode).
 */
public record UpdateStrategyInstanceRequest(
        @Size(max = 64) String symbol,
        @Size(max = 16) String executionMode,
        @DecimalMin("0") BigDecimal allocationAmount,
        @DecimalMin("0") BigDecimal riskMultiplier,
        @DecimalMin("0") BigDecimal maxDailyLoss
) {
}
