package com.stokr.user.dto;

import java.util.UUID;

/** Aggregated self-service status for workstation onboarding UI. */
public record TraderOnboardingSummaryDto(
        UUID userId,
        boolean emailVerified,
        boolean telegramVerified,
        boolean whatsAppVerified,
        boolean onboardingComplete,
        boolean liveTradingApproved,
        boolean zerodhaBrokerConnected,
        String telegramUsername,
        String whatsAppE164
) {
}
