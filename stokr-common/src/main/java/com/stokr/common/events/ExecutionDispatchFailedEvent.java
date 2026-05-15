package com.stokr.common.events;

import java.util.UUID;

/**
 * Published when the execution consumer fails processing a dispatch message (before DLQ / retry).
 */
public record ExecutionDispatchFailedEvent(UUID orderId, UUID userId, UUID signalId, String reason) {
}
