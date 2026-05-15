package com.stokr.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public record MarketBackfillCreateRequest(
        @NotBlank String brokerSource,
        @NotBlank String symbolGroup,
        List<String> customSymbols,
        @NotBlank String timeframe,
        @NotNull Instant rangeStart,
        @NotNull Instant rangeEnd
) {
}
