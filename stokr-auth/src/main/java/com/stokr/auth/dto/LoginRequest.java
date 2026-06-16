package com.stokr.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        /**
         * Email address or username.
         */
        @NotBlank String principal,
        @NotBlank String password
) {
}
