package com.stokr.common.domain;

public enum ExitReason {
    TARGET_HIT("Position reached profit target"),
    STOP_LOSS_HIT("Position reached stop loss");

    private final String description;

    ExitReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
