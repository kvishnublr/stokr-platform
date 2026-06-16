package com.stokr.strategy.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record StrategyCatalogItemDto(
        UUID id,
        String code,
        String name,
        String description,
        String riskLevel,
        boolean subscribed,
        boolean subscriptionEnabled,
        String category,
        String assetClass,
        String segment,
        String tagsJson,
        String iconKey,
        BigDecimal minCapital,
        BigDecimal popularityScore,
        BigDecimal winRate,
        BigDecimal avgMonthlyReturn,
        String announcementBanner,
        String executionMode,
        String runtimeState,
        BigDecimal allocationAmount,
        BigDecimal riskMultiplier,
        List<String> universeGroups
) {
}
