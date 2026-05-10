package com.stokr.common.events.realtime;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight notifications bridged to WebSocket/STOMP subscribers (see {@code stokr-websocket}).
 */
public final class RealtimeBridgeEvents {

    private RealtimeBridgeEvents() {
    }

    public record OrderUpdate(UUID userId, UUID orderId, String symbol, String orderState, Instant at) {
    }

    public record PnlUpdated(UUID userId, Instant at) {
    }

    public record StrategyRuntime(UUID userId, UUID instanceId, String runtimeState, Instant at) {
    }
}
