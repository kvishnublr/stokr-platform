package com.stokr.auth.dto;

import java.util.Set;
import java.util.UUID;

/** {@code verificationEmailStatus} is non-null only on {@code /register}: NOT_CONFIGURED, SENT, SEND_FAILED. */
public record AuthResponse(
        UUID userId,
        String username,
        String email,
        String displayName,
        Set<String> roles,
        boolean emailVerified,
        boolean telegramVerified,
        boolean whatsappVerified,
        boolean onboardingComplete,
        boolean liveTradingApproved,
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        String verificationEmailStatus
) {
}
