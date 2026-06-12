package com.stokr.common.events;

import java.util.Map;
import java.util.UUID;

/**
 * Publishes {@link OperationalRealtimeEvent} for signal exits inside the active transaction so
 * {@code @TransactionalEventListener(AFTER_COMMIT)} consumers (e.g. auto-exit OMS placement) run reliably.
 */
public final class SignalOutcomeEvents {

    private SignalOutcomeEvents() {
    }

    public static OperationalRealtimeEvent outcome(
            UUID signalId,
            String symbol,
            String strategyKey,
            String outcomeStatus,
            UUID userId,
            String realizedPnl,
            String exitReason,
            String exitCategory) {
        return new OperationalRealtimeEvent(
                "signal_outcome",
                Map.of(
                        "signalId", signalId.toString(),
                        "symbol", symbol != null ? symbol : "",
                        "strategyKey", strategyKey != null ? strategyKey : "",
                        "outcomeStatus", outcomeStatus != null ? outcomeStatus : "",
                        "realizedPnl", realizedPnl != null ? realizedPnl : "0",
                        "userId", userId != null ? userId.toString() : "system",
                        "exitReason", exitReason != null ? exitReason : "",
                        "exitCategory", exitCategory != null ? exitCategory : ""
                )
        );
    }

    public static OperationalRealtimeEvent outcome(
            UUID signalId,
            String symbol,
            String strategyKey,
            String outcomeStatus,
            UUID userId,
            String realizedPnl) {
        return outcome(signalId, symbol, strategyKey, outcomeStatus, userId, realizedPnl, null, null);
    }
}
