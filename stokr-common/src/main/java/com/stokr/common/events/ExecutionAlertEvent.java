package com.stokr.common.events;

import java.util.UUID;

public record ExecutionAlertEvent(
        String alertType,
        String strategyKey,
        String symbol,
        UUID orderId,
        UUID userId,
        String text
) {
}
