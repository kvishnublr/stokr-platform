package com.stokr.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RuntimeBindingDto(
        UUID id,
        UUID strategyCatalogId,
        String strategyKey,
        String strategyDisplayName,
        String assetClass,
        String segment,
        UUID universeGroupId,
        String groupKey,
        String groupDisplayName,
        String instrumentType,
        boolean runtimeEnabled,
        int maxPositions,
        BigDecimal capitalLimit,
        String riskProfile,
        int scanIntervalSeconds,
        long symbolCount,
        Instant createdAt,
        Instant updatedAt
) {}
