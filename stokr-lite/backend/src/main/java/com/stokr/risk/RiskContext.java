package com.stokr.risk;

import java.math.BigDecimal;

/**
 * Context passed to risk rules for evaluation.
 */
public record RiskContext(
        Long deploymentId,
        Long userId,
        String symbol,
        int orderQuantity,
        BigDecimal orderPrice,
        int currentOpenPositions,
        BigDecimal dailyPnl,
        BigDecimal availableCapital,
        BigDecimal totalDeployedCapital,
        int maxPositions,
        BigDecimal maxDailyLoss,
        int maxQtyPerTrade,
        long lastSignalTimeMs
) {}
