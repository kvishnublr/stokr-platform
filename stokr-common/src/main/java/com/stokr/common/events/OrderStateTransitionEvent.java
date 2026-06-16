package com.stokr.common.events;

import java.util.UUID;

/**
 * Emitted by the OMS order lifecycle whenever an order reaches a fill or terminal-failure
 * state. Lets higher modules (execution alerting, admin telemetry) react to broker
 * rejections and live fills without the OMS module depending on them.
 */
public record OrderStateTransitionEvent(
        UUID orderId,
        UUID userId,
        String symbol,
        String strategyKey,
        String executionMode,
        String previousState,
        String newState,
        String rejectReason
) {
}
