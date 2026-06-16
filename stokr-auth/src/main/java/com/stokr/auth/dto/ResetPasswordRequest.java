package com.stokr.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank
        @Size(min = 12, max = 128)
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9])[\\S]{12,128}$",
                message = "Password must be at least 12 characters and include upper, lower, digit, and symbol"
        )
        String newPassword,
        @NotBlank String confirmPassword
) {
}
