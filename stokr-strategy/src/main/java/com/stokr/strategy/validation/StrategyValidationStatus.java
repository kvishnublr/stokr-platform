package com.stokr.strategy.validation;

public enum StrategyValidationStatus {
    RESEARCH,
    DRY_RUN,
    PAPER_VALIDATING,
    LIVE_SHADOW,
    LIVE_CANDIDATE,
    LIVE_VALIDATED,
    RETIRED;

    public static StrategyValidationStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DRY_RUN;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return DRY_RUN;
        }
    }

    public boolean allowsPaperExecution() {
        return this != RESEARCH && this != RETIRED && this != DRY_RUN;
    }

    public boolean allowsLiveShadow() {
        return this == LIVE_SHADOW || this == LIVE_CANDIDATE || this == LIVE_VALIDATED;
    }
}
