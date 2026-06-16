package com.stokr.strategy.service;

import com.stokr.strategy.domain.StrategySignalEntity;

/**
 * Keeps {@code lifecycle_status} aligned with canonical outcome states for analytics dashboards.
 */
public final class SignalLifecycleService {

    private SignalLifecycleService() {
    }

    public static void applyInitial(StrategySignalEntity signal, String outcomeStatus) {
        if (signal == null) {
            return;
        }
        String status = outcomeStatus != null && !outcomeStatus.isBlank() ? outcomeStatus : "PENDING";
        signal.setOutcomeStatus(status);
        signal.setLifecycleStatus(status);
    }

    public static void syncFromOutcome(StrategySignalEntity signal) {
        if (signal == null) {
            return;
        }
        String outcome = signal.getOutcomeStatus();
        if (outcome != null && !outcome.isBlank()) {
            signal.setLifecycleStatus(outcome);
        }
    }

    public static void updateOutcome(StrategySignalEntity signal, String outcomeStatus) {
        if (signal == null || outcomeStatus == null || outcomeStatus.isBlank()) {
            return;
        }
        signal.setOutcomeStatus(outcomeStatus);
        signal.setLifecycleStatus(outcomeStatus);
    }
}
