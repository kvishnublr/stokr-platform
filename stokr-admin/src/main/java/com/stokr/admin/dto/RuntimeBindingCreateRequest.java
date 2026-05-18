package com.stokr.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record RuntimeBindingCreateRequest(

        @NotNull
        UUID strategyCatalogId,

        @NotNull
        UUID universeGroupId,

        Boolean runtimeEnabled,

        @Min(1)
        Integer maxPositions,

        BigDecimal capitalLimit,

        /** LOW | MEDIUM | HIGH */
        String riskProfile,

        @Min(10)
        Integer scanIntervalSeconds
) {}
