package com.stokr.strategy.operational;

import java.util.Locale;

public enum StrategyExecutionMode {
    LIVE,
    PAPER,
    /** Dual paper + live broker legs (admin/trader execution config BOTH). */
    BOTH,
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

    /** Pipeline label stored on signals and passed into OMS dispatch. */
    public String pipelineLabel() {
        return name();
    }
}
