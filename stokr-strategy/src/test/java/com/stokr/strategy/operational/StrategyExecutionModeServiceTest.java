package com.stokr.strategy.operational;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategyExecutionModeServiceTest {

    private static final String LIVE_COHORT =
            "GAP_FILL,NSE_SPIKE_DETECTION,VWAP_BOUNCE,SECTOR_LAGGARD,ADV_CASH";

    @Test
    void defaultsMatchGoLiveOperationalPlan() {
        StrategyExecutionModeService service = new StrategyExecutionModeService(
                "LIVE", "LIVE", "LIVE", "PAPER", "LIVE",
                "PAPER", "LIVE", "PAPER", "PAPER", "PAPER", "PAPER",
                "PAPER", "PAPER",
                true, LIVE_COHORT);

        assertEquals(StrategyExecutionMode.LIVE, service.modeFor("GAP_FILL"));
        assertEquals(StrategyExecutionMode.LIVE, service.modeFor("SECTOR_LAGGARD"));
        assertEquals(StrategyExecutionMode.LIVE, service.modeFor("NSE_SPIKE_DETECTION"));
        assertEquals(StrategyExecutionMode.LIVE, service.modeFor("VWAP_BOUNCE"));
        assertEquals(StrategyExecutionMode.LIVE, service.modeFor("ADV_CASH"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("EARLY_BREAKOUT"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("INDEX_HUNT"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("S3_VWAP_RETEST"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("S7_RANGE_FADE"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("PRE_OPEN_GAP_OI"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("COMMODITIES_E2E_TEST"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("USDINR_MOMENTUM"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("EURINR_MEAN_REVERSION"));
    }

    @Test
    void liveModeDowngradedWhenNotValidated() {
        StrategyExecutionModeService service = new StrategyExecutionModeService(
                "LIVE", "LIVE", "LIVE", "PAPER", "LIVE",
                "PAPER", "LIVE", "PAPER", "PAPER", "PAPER", "PAPER",
                "PAPER", "PAPER",
                false, LIVE_COHORT);

        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("GAP_FILL"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("NSE_SPIKE_DETECTION"));
    }

    @Test
    void liveModeAllowedOnlyWhenExplicitlyValidated() {
        StrategyExecutionModeService service = new StrategyExecutionModeService(
                "LIVE", "LIVE", "LIVE", "PAPER", "LIVE",
                "PAPER", "LIVE", "PAPER", "PAPER", "PAPER", "PAPER",
                "PAPER", "PAPER",
                true, LIVE_COHORT);

        assertEquals(StrategyExecutionMode.LIVE, service.modeFor("GAP_FILL"));
        assertEquals(StrategyExecutionMode.LIVE, service.modeFor("SECTOR_LAGGARD"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("EARLY_BREAKOUT"));
    }

    @Test
    void liveCohortRequiresAllowLiveFlag() {
        StrategyExecutionModeService service = new StrategyExecutionModeService(
                "LIVE", "LIVE", "LIVE", "PAPER", "LIVE",
                "PAPER", "LIVE", "PAPER", "PAPER", "PAPER", "PAPER",
                "PAPER", "PAPER",
                true, "GAP_FILL");
        assertEquals(StrategyExecutionMode.LIVE, service.modeFor("GAP_FILL"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("NSE_SPIKE_DETECTION"));
    }
}
