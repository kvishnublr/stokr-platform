package com.stokr.strategy.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyLifecycleProfileTest {

    @Test
    void gapFillHasFifteenMinuteMinHoldAndSingleSessionEntry() {
        StrategyLifecycleProfile profile = StrategyLifecycleProfile.forStrategy("GAP_FILL");
        assertEquals(900, profile.minHoldSeconds());
        assertFalse(profile.allowReentry());
        assertEquals(1, profile.maxEntriesPerSymbolPerSession());
        assertTrue(profile.pressureExitEnabled());
    }

    @Test
    void spikeDetectionHasZeroMinHold() {
        StrategyLifecycleProfile profile = StrategyLifecycleProfile.forStrategy("NSE_SPIKE_DETECTION");
        assertEquals(0, profile.minHoldSeconds());
    }

    @Test
    void sectorLaggardHasTenMinuteMinHold() {
        StrategyLifecycleProfile profile = StrategyLifecycleProfile.forStrategy("SECTOR_LAGGARD");
        assertEquals(600, profile.minHoldSeconds());
    }

    @Test
    void exitCategoryMapsToOutcomeStatus() {
        assertEquals("TIME_EXIT", ExitCategory.TIME_EXIT.outcomeStatus());
        assertEquals("FEED_PROTECTION", ExitCategory.FEED_PROTECTION.outcomeStatus());
        assertTrue(ExitCategory.isTerminalOutcome("TIME_EXIT"));
        assertTrue(ExitCategory.isTerminalOutcome("FEED_PROTECTION"));
    }
}
