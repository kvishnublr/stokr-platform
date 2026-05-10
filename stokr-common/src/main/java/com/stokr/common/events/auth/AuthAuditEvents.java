package com.stokr.common.events.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain events published by the auth module; consumed for audit trails / integrations.
 */
public final class AuthAuditEvents {

    private AuthAuditEvents() {
    }

    public record LoginSucceeded(UUID userId, String email, Instant at) {
    }

    public record LoginFailed(String principal, String reason, Instant at) {
    }

    public record Logout(UUID userId, Instant at) {
    }

    public record RefreshRotated(UUID userId, Instant at) {
    }

    public record UserRegistered(UUID userId, String email, Instant at) {
    }

    public record PasswordResetRequested(UUID userId, String email, Instant at) {
    }

    public record PasswordResetCompleted(UUID userId, boolean selfService, Instant at) {
    }

    public record AccountStatusChanged(UUID targetUserId, boolean enabled, UUID actorUserId, Instant at) {
    }

    /** Placeholder for future SendGrid/SES integration — emit when verification flow is implemented. */
    public record EmailVerificationRequested(UUID userId, Instant at) {
    }

    public record EmailVerified(UUID userId, String email, Instant at) {
    }

    public record TelegramVerified(UUID userId, String telegramChatId, Instant at) {
    }

    public record WhatsappVerified(UUID userId, String e164, Instant at) {
    }

    public record BrokerZerodhaConnected(UUID userId, UUID brokerAccountId, Instant at) {
    }
}
