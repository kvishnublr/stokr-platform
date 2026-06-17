package com.stokr.strategy.operational;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategyExecutionModeServiceTest {

    private static final String LIVE_COHORT =
            "VWAP_TRIPLE_CONFIRMATION,TRADE_BOOK_IMBALANCE,PRE_OPEN_GAP_OI,ORB_V,MORNING_SURGE";

    @Test
    void defaultsMatchGoLiveOperationalPlan() {
        StrategyExecutionModeService service = new StrategyExecutionModeService(
                "BOTH", "BOTH", "BOTH", "BOTH", "BOTH",
                true, LIVE_COHORT, null);

        assertEquals(StrategyExecutionMode.BOTH, service.modeFor("VWAP_TRIPLE_CONFIRMATION"));
        assertEquals(StrategyExecutionMode.BOTH, service.modeFor("TRADE_BOOK_IMBALANCE"));
        assertEquals(StrategyExecutionMode.BOTH, service.modeFor("PRE_OPEN_GAP_OI"));
        assertEquals(StrategyExecutionMode.BOTH, service.modeFor("ORB_V"));
        assertEquals(StrategyExecutionMode.BOTH, service.modeFor("MORNING_SURGE"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("UNKNOWN_STRATEGY"));
    }

    @Test
    void liveModeDowngradedWhenNotValidated() {
        StrategyExecutionModeService service = new StrategyExecutionModeService(
                "LIVE", "LIVE", "LIVE", "LIVE", "LIVE",
                false, LIVE_COHORT, null);

        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("VWAP_TRIPLE_CONFIRMATION"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("MORNING_SURGE"));
    }

    @Test
    void liveModeAllowedOnlyWhenExplicitlyValidated() {
        StrategyExecutionModeService service = new StrategyExecutionModeService(
                "LIVE", "LIVE", "LIVE", "LIVE", "LIVE",
                true, "ORB_V,MORNING_SURGE", null);

        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("VWAP_TRIPLE_CONFIRMATION"));
        assertEquals(StrategyExecutionMode.LIVE, service.modeFor("ORB_V"));
        assertEquals(StrategyExecutionMode.LIVE, service.modeFor("MORNING_SURGE"));
    }

    @Test
    void liveCohortRequiresAllowLiveFlag() {
        StrategyExecutionModeService service = new StrategyExecutionModeService(
                "LIVE", "LIVE", "LIVE", "LIVE", "LIVE",
                true, "VWAP_TRIPLE_CONFIRMATION", null);

        assertEquals(StrategyExecutionMode.LIVE, service.modeFor("VWAP_TRIPLE_CONFIRMATION"));
        assertEquals(StrategyExecutionMode.PAPER, service.modeFor("MORNING_SURGE"));
    }
}
