package com.stokr.user.dto;

import jakarta.validation.constraints.NotBlank;

public record TraderExecutionModeUpdateRequest(
        @NotBlank String executionMode
) {
}

