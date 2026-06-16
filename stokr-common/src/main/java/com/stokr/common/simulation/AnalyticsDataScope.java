package com.stokr.common.simulation;

/**
 * Analytics lens for strategy effectiveness, alpha validation, and admin dashboards.
 */
public enum AnalyticsDataScope {
    /** Production truth — excludes simulation, test lab, and backtest rows. */
    REAL,
    /** Harness / E2E simulation runs only. */
    SIMULATION,
    /** All non-deleted rows (audit / ops only). */
    MIXED;

    public static AnalyticsDataScope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return REAL;
        }
        return AnalyticsDataScope.valueOf(raw.trim().toUpperCase());
    }
}
