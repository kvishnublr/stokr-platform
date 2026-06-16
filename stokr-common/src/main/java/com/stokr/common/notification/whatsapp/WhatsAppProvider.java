package com.stokr.common.notification.whatsapp;

import java.time.Instant;

/**
 * Provider abstraction for WhatsApp OTP / transactional messaging (Twilio, Meta Cloud API, BSP partners).
 */
public interface WhatsAppProvider {

    String providerKey();

    void sendVerificationOtp(String e164, String otpCode, Instant expiresAt);
}
