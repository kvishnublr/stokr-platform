package com.stokr.common.simulation;

/**
 * SQL scope fragments for {@code oms_orders} analytics (no table alias).
 */
public final class OmsAnalyticsFilters {

    private OmsAnalyticsFilters() {
    }

    /**
     * Scope predicate for native queries on {@code oms_orders}.
     */
    public static String orderScopeFilter(AnalyticsDataScope scope) {
        return switch (scope) {
            case REAL -> """
                    is_simulation = FALSE
                    AND is_test_trade = FALSE
                    AND backtest_run_id IS NULL
                    """;
            case SIMULATION -> "is_simulation = TRUE";
            case MIXED -> "TRUE";
        };
    }
}
