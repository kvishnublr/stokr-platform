package com.stokr.common.notification;

import java.util.Map;
import java.util.UUID;

/**
 * Cross-cutting runtime/trader notification envelope (Telegram/WhatsApp/email adapters consume).
 */
public record NotificationEvent(
        String channel,
        String templateKey,
        UUID userId,
        Map<String, String> payload
) {
}
