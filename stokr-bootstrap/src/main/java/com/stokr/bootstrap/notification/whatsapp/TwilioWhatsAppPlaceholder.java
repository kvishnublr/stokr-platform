package com.stokr.bootstrap.notification.whatsapp;

import com.stokr.common.notification.whatsapp.WhatsAppProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Drop-in placeholder until Twilio WhatsApp sender credentials are configured.
 * Registered as a bean via {@link com.stokr.bootstrap.config.WhatsAppProviderConfig}.
 * Swap with a Meta Cloud API adapter implementing WhatsAppProvider the same way.
 */
public class TwilioWhatsAppPlaceholder implements WhatsAppProvider {

    private static final Logger log = LoggerFactory.getLogger(TwilioWhatsAppPlaceholder.class);

    @Override
    public String providerKey() {
        return "twilio-placeholder";
    }

    @Override
    public void sendVerificationOtp(String e164, String otpCode, Instant expiresAt) {
        log.info("[whatsapp-placeholder] would send OTP to {} expiring {}", mask(e164), expiresAt);
    }

    private static String mask(String e164) {
        if (e164 == null || e164.length() < 6) {
            return "***";
        }
        return e164.substring(0, 3) + "…" + e164.substring(e164.length() - 2);
    }
}
