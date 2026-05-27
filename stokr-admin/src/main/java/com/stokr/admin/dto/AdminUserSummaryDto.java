package com.stokr.admin.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record AdminUserSummaryDto(
        UUID id,
        String username,
        String email,
        String displayName,
        boolean enabled,
        Set<String> roles,
        Instant createdAt,
        Instant lastLoginAt,
        long activeStrategies,
        boolean brokerLinked,
        boolean liveTradingApproved
) {
}
