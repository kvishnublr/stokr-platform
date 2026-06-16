package com.stokr.oms.trace;

import java.util.Map;

/**
 * Read-model row for admin execution timeline (parsed from {@code oms_execution_events}).
 */
public record ExecutionTraceEvent(
        String eventType,
        Long streamSequence,
        String createdAt,
        Map<String, Object> payload
) {
}
