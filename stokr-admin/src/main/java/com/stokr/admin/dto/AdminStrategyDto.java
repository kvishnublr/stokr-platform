package com.stokr.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
        // catalog fields (V31)
        String strategyType,
        String executionMode,
        String assetClass,
        String segment,
        String defaultTimeframe,
        String defaultExchange,
        boolean supportsBacktest,
        boolean supportsLive,
        boolean supportsPaper,
        boolean derivativeEnabled,
        boolean futuresStrategyEnabled,
        boolean optionStrategyEnabled,
        String templateClassName,
        String generatedClassPath,
        boolean templateGenerated,
        String catalogVersion,
        Instant createdAt,
        List<String> symbols
) {}
