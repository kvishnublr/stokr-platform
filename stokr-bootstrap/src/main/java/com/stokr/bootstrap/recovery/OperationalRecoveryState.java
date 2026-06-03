package com.stokr.bootstrap.recovery;

import java.time.Instant;

/**
 * Persisted recovery state per service/container to prevent reconnect loops.
 */
public record OperationalRecoveryState(
        OperationalFailureSignature failureSignature,
        RecoveryActionType lastActionTaken,
        int attemptCount,
        int consecutiveUnhealthyCycles,
        Instant lastActionAt,
        Instant lastSuccessAt,
        Instant updatedAt
) {
    public static OperationalRecoveryState empty() {
        return new OperationalRecoveryState(null, RecoveryActionType.NONE, 0, 0, null, null, Instant.now());
    }

    public OperationalRecoveryState withAction(
            OperationalFailureSignature signature,
            RecoveryActionType action,
            Instant now
    ) {
        int nextAttempts = signature == this.failureSignature && action == this.lastActionTaken
                ? attemptCount + 1
                : 1;
        return new OperationalRecoveryState(signature, action, nextAttempts, consecutiveUnhealthyCycles + 1, now, lastSuccessAt, now);
    }

    public OperationalRecoveryState withSuccess(Instant now) {
        return new OperationalRecoveryState(null, RecoveryActionType.NONE, 0, 0, lastActionAt, now, now);
    }
}
