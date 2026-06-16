package com.stokr.oms.execution;

import com.stokr.oms.domain.OrderState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

/**
 * Validates legal OMS state transitions. Keeps execution paths auditable and replay-safe.
 */
public final class OrderStateMachine {

    private static final EnumMap<OrderState, Set<OrderState>> ALLOWED = new EnumMap<>(OrderState.class);

    static {
        put(OrderState.CREATED, OrderState.VALIDATED);
        put(OrderState.VALIDATED, OrderState.RISK_CHECK);
        put(OrderState.RISK_CHECK, OrderState.REJECTED, OrderState.PENDING_SUBMISSION, OrderState.FAILED);
        put(OrderState.PENDING_SUBMISSION, OrderState.SUBMITTED, OrderState.REJECTED, OrderState.FAILED);
        put(OrderState.SUBMITTED, OrderState.ACCEPTED, OrderState.REJECTED, OrderState.FAILED);
        put(OrderState.ACCEPTED, OrderState.PARTIALLY_FILLED, OrderState.FILLED, OrderState.CANCEL_REQUESTED, OrderState.FAILED);
        put(OrderState.PARTIALLY_FILLED, OrderState.PARTIALLY_FILLED, OrderState.FILLED, OrderState.CANCEL_REQUESTED, OrderState.FAILED);
    }

    private static void put(OrderState from, OrderState... tos) {
        ALLOWED.put(from, EnumSet.copyOf(Set.of(tos)));
    }

    public static void validate(OrderState from, OrderState to) {
        if (from == to) {
            return;
        }
        Set<OrderState> next = ALLOWED.get(from);
        if (next == null || !next.contains(to)) {
            throw new IllegalStateException("Illegal order state transition: " + from + " -> " + to);
        }
    }
}
