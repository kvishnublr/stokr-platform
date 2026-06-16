package com.stokr.common.events;

import java.util.Map;

/**
 * Generic admin-ops realtime envelope (SSE bridge). Published on the application event bus.
 */
public record OperationalRealtimeEvent(String topic, Map<String, Object> payload) {
}
