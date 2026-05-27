package com.stokr.strategy.operational;

import java.util.Locale;

public enum StrategyExecutionMode {
    LIVE,
    PAPER,
    DRY_RUN,
    DISABLED;

    public static StrategyExecutionMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PAPER;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return PAPER;
        }
    }

    public boolean skipsScheduler() {
        return this == DISABLED;
    }

    public boolean skipsSignalPersist() {
        return this == DRY_RUN;
    }

    public boolean skipsBrokerExecution() {
        return this == PAPER || this == DRY_RUN;
    }
}
