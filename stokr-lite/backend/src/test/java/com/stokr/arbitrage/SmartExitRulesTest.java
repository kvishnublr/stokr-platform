package com.stokr.arbitrage;

import org.junit.jupiter.api.Test;

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
}
