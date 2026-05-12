package com.stokr.auth.dto;

/** Result of {@code POST /api/auth/resend-verification} — status is NOT_CONFIGURED, SENT, or SEND_FAILED. */
public record ResendVerificationResponse(String status) {
}
