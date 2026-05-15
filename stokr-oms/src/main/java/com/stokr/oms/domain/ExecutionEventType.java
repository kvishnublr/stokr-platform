package com.stokr.oms.domain;

/**
 * Append-only execution / orchestration events (PR-4). Stored in {@code oms_execution_events.event_type}.
 */
public enum ExecutionEventType {
    SIGNAL_GENERATED,
    RISK_CHECK_PASSED,
    ORDER_REQUESTED,
    ORDER_ACCEPTED,
    /** Dispatched to execution plane (sync call or execution queue). */
    EXECUTION_DISPATCHED,
    /** LIVE: order handed to broker adapter; SIM: synthetic broker submit. */
    BROKER_SUBMITTED,
    /** Simulated / logical exchange acknowledgement before fills. */
    EXCHANGE_ACK,
    PARTIAL_FILL,
    ORDER_FILLED,
    STOP_TRIGGERED,
    POSITION_CLOSED,
    EXECUTION_REJECTED,
    EXECUTION_RETRY_SCHEDULED,
    EXECUTION_DLQ
}
