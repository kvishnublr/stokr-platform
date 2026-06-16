package com.stokr.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min = 1, max = 200) String displayName,
        @NotBlank
        @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{2,31}$", message = "Username: 3–32 chars, letters, digits, underscore; must start with a letter")
        String username,
        @NotBlank @Email String email,
        @NotBlank
        @Size(min = 12, max = 128)
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9])[\\S]{12,128}$",
                message = "Password must be at least 12 characters and include upper, lower, digit, and symbol"
        )
        String password,
        @NotBlank String confirmPassword,
        @Size(max = 32) String mobilePhone,
        @Size(max = 64) String telegramUsername,
        @Size(max = 32) String whatsAppNumber
) {
}
