package com.stokr.admin.dto;

import java.math.BigDecimal;

public record AdminStrategyPatchRequest(
        Boolean enabled,
        Boolean visibleToUsers,
        String riskLevel,
        String displayName,
        String category,
        String tagsJson,
        String iconKey,
        BigDecimal minCapital,
        BigDecimal popularityScore,
        BigDecimal winRate,
        BigDecimal avgMonthlyReturn,
        String announcementBanner
) {
}
