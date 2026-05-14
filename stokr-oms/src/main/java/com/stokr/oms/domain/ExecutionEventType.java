package com.stokr.oms.domain;

/**
 * Append-only execution / orchestration events (PR-4). Stored in {@code oms_execution_events.event_type}.
 */
public enum ExecutionEventType {
    SIGNAL_GENERATED,
    RISK_CHECK_PASSED,
    ORDER_REQUESTED,
    ORDER_ACCEPTED,
    PARTIAL_FILL,
    ORDER_FILLED,
    STOP_TRIGGERED,
    POSITION_CLOSED,
    EXECUTION_REJECTED
}
