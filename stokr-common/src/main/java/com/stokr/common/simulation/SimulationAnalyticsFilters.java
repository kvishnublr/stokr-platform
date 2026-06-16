package com.stokr.common.simulation;

/**
 * SQL fragments for {@link AnalyticsDataScope} on {@code strategy_signals} alias {@code s}.
 */
public final class SimulationAnalyticsFilters {

    private SimulationAnalyticsFilters() {
    }

    public static String signalScopeFilter(AnalyticsDataScope scope) {
        return switch (scope) {
            case REAL -> """
                    s.is_simulation = FALSE
                    AND s.is_test_trade = FALSE
                    AND s.backtest_run_id IS NULL
                    AND (s.signal_source IS NULL OR s.signal_source IN ('LIVE', 'PAPER'))
                    """;
            case SIMULATION -> "s.is_simulation = TRUE";
            case MIXED -> "TRUE";
        };
    }
}
