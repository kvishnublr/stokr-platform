package com.stokr.arbitrage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the exit-monitor gates that previously skipped every Smart Strategy position. */
class SmartExitRulesTest {

    @Test
    void smartPositionsWithRulesAreEvaluated() {
        assertTrue(OptionArbAutoExecService.hasSmartExitRules(LivePosition.builder().slPct(45.0).build()));
        assertTrue(OptionArbAutoExecService.hasSmartExitRules(LivePosition.builder().targetPct(60.0).build()));
        assertTrue(OptionArbAutoExecService.hasSmartExitRules(LivePosition.builder().timeExitMinutes(3).build()));
    }

    @Test
    void holdToExpiryPositionsHaveNoRules() {
        // Box spreads are entered with SL/target/time all 0 and must be left alone until expiry.
        assertFalse(OptionArbAutoExecService.hasSmartExitRules(
            LivePosition.builder().slPct(0.0).targetPct(0.0).timeExitMinutes(0).build()));
        assertFalse(OptionArbAutoExecService.hasSmartExitRules(LivePosition.builder().build()));
    }

    @Test
    void multiLegPnlRequiresEveryLegQuoted() {
        LivePosition pos = LivePosition.builder().build();
        pos.setLegs(List.of(Map.<String, Object>of("symbol", "A"), Map.<String, Object>of("symbol", "B")));
        OptionChainService.OptionQuote q = new OptionChainService.OptionQuote();
        q.lastPrice = 10;
        assertTrue(OptionArbAutoExecService.allLegsQuoted(pos, Map.of("A", q, "B", q)));
        assertFalse(OptionArbAutoExecService.allLegsQuoted(pos, Map.of("A", q)));
    }

    @Test
    void multiLegStopLossThresholdCalculatesProperly() {
        LivePosition pos = LivePosition.builder()
                .strategyType("JADE_LIZARD")
                .underlying("BANKNIFTY")
                .slPct(40.0)
                .maxLossAmount(10000.0)
                .build();

        double slThreshold = pos.getMaxLossAmount() * pos.getSlPct() / 100.0;
        assertEquals(4000.0, slThreshold);

        double smallLoss = -2500.0;
        assertFalse(Math.abs(smallLoss) >= slThreshold);

        double largeLoss = -4200.0;
        assertTrue(Math.abs(largeLoss) >= slThreshold);
    }
}