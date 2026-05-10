package com.stokr.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminStrategyDto(
        UUID id,
        String code,
        String displayName,
        String description,
        boolean enabled,
        boolean visibleToUsers,
        String riskLevel,
        String category,
        String tagsJson,
        String iconKey,
        BigDecimal minCapital,
        BigDecimal popularityScore,
        BigDecimal winRate,
        BigDecimal avgMonthlyReturn,
        String announcementBanner,
        Instant createdAt
) {
}
