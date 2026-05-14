package com.stokr.oms.domain;

/**
 * Order / execution lifecycle (PR-4). Persisted as {@code VARCHAR} on {@code oms_orders.state}.
 * <p>Terminal-ish states: {@link #REJECTED}, {@link #FILLED}, {@link #CANCELLED}, {@link #EXIT_FILLED},
 * {@link #FAILED}, {@link #EXPIRED}.</p>
 */
public enum OrderState {
    CREATED,
    VALIDATED,
    RISK_CHECK,
    REJECTED,
    PENDING_SUBMISSION,
    SUBMITTED,
    ACCEPTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCEL_REQUESTED,
    CANCELLED,
    EXIT_REQUESTED,
    EXIT_FILLED,
    FAILED,
    EXPIRED
}
