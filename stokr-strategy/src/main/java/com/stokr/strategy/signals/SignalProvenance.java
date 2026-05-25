package com.stokr.strategy.signals;

/**
 * Production analytics must use {@link #LIVE} and {@link #PAPER} only.
 * {@link #REPLAY} and {@link #LAB} are excluded from expectancy, ranking, and default admin dashboards.
 */
public enum SignalProvenance {
    LIVE,
    PAPER,
    REPLAY,
    LAB;

    public boolean isProductionAnalytics() {
        return this == LIVE || this == PAPER;
    }
}
