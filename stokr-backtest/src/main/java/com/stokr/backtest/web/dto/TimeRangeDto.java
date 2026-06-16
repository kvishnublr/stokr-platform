package com.stokr.backtest.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record TimeRangeDto(
        @NotNull Instant from,
        @NotNull Instant to,
        @NotBlank @Size(max = 64) String timezone
) {
}
