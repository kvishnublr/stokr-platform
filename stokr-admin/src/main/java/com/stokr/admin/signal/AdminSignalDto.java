package com.stokr.admin.signal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminSignalDto(
        UUID id,
        String strategyName,
        String symbol,
        String signalType,
        String pipeline,
        BigDecimal confidenceScore,
        BigDecimal entryReferencePrice,
        BigDecimal stopPrice,
        BigDecimal targetPrice,
        BigDecimal suggestedQty,
        String reason,
        String marketRegime,
        UUID userId,
        Instant createdAt,
        // Outcome lifecycle
        String outcomeStatus,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        BigDecimal maxFavorableExcursion,
        BigDecimal maxAdverseExcursion,
        Boolean hitTarget,
        Boolean hitStoploss,
        BigDecimal riskRewardAchieved,
        Long executionLatencyMs,
        BigDecimal entryPrice,
        BigDecimal exitPrice
) {}
