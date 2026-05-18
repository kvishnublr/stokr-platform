package com.stokr.admin.dto;

import java.time.Instant;
import java.util.UUID;

public record UniverseGroupDto(
        UUID id,
        String groupKey,
        String displayName,
        String description,
        String universeType,
        String exchange,
        String assetClass,
        String segment,
        String instrumentType,
        boolean autoManaged,
        boolean enabled,
        long symbolCount,
        Instant createdAt,
        Instant updatedAt
) {}
