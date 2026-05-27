package com.stokr.strategy.operational;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategyExecutionModeServiceTest {

    @Test
    void defaultsMatchTomorrowOperationalPlan() {
        StrategyExecutionModeService service = new StrategyExecutionModeService(
                "PAPER", "DISABLED", "DRY_RUN", "DRY_RUN", "DRY_RUN",
                "DRY_RUN", "DRY_RUN", "DISABLED", "DISABLED",
                false, "");

        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("GAP_FILL"));
        assertEquals(StrategyExecutionMode.DISABLED, service.modeFor("SECTOR_LAGGARD"));
        assertEquals(StrategyExecutionMode.DRY_RUN, service.modeFor("NSE_SPIKE_DETECTION"));
        assertEquals(StrategyExecutionMode.DRY_RUN, service.modeFor("EARLY_BREAKOUT"));
        assertEquals(StrategyExecutionMode.DRY_RUN, service.modeFor("VWAP_BOUNCE"));
        assertEquals(StrategyExecutionMode.DRY_RUN, service.modeFor("INDEX_HUNT"));
        assertEquals(StrategyExecutionMode.DRY_RUN, service.modeFor("ADV_CASH"));
        assertEquals(StrategyExecutionMode.DISABLED, service.modeFor("S3_VWAP_RETEST"));
        assertEquals(StrategyExecutionMode.DISABLED, service.modeFor("S7_RANGE_FADE"));
    }

    @Test
    void liveModeDowngradedWhenNotValidated() {
        StrategyExecutionModeService service = new StrategyExecutionModeService(
                "LIVE", "DISABLED", "DRY_RUN", "DRY_RUN", "DRY_RUN",
                "DRY_RUN", "DRY_RUN", "DISABLED", "DISABLED",
                false, "GAP_FILL");

        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("GAP_FILL"));
    }

    @Test
    void liveModeAllowedOnlyWhenExplicitlyValidated() {
        StrategyExecutionModeService service = new StrategyExecutionModeService(
                "LIVE", "DISABLED", "DRY_RUN", "DRY_RUN", "DRY_RUN",
                "DRY_RUN", "DRY_RUN", "DISABLED", "DISABLED",
                true, "GAP_FILL");

        assertEquals(StrategyExecutionMode.LIVE, service.modeFor("GAP_FILL"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("NSE_SPIKE_DETECTION"));
    }

    @Test
    void onlyGapFillEligibleForLiveByDefaultPolicy() {
        StrategyExecutionModeService service = new StrategyExecutionModeService(
                "PAPER", "DISABLED", "DRY_RUN", "DRY_RUN", "DRY_RUN",
                "DRY_RUN", "DRY_RUN", "DISABLED", "DISABLED",
                true, "GAP_FILL");
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("GAP_FILL"));
        assertEquals(StrategyExecutionMode.DRY_RUN, service.modeFor("NSE_SPIKE_DETECTION"));
    }
}
