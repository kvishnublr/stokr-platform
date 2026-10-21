package com.stokr.arbitrage;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntryCooldownTest {

    private static LivePosition p(String strat, String und, String broker, int minutesAgo, String status) {
        LivePosition p = new LivePosition();
        p.setStrategyType(strat); p.setUnderlying(und); p.setBroker(broker); p.setStatus(status);
        p.setEnteredAt(NOW.minusMinutes(minutesAgo));
        return p;
    }
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 9, 32);

    @Test
    void sameStrategySameUnderlyingIsBlockedWhateverTheStrike() {
        // 21-Oct: Jade Lizard 55600 at 09:30:55, then 55500 at 09:31:57.
        var recent = List.of(p("JADE_LIZARD", "BANKNIFTY", "PAPER", 1, "OPEN"));
        assertTrue(EntryCooldown.blocked(recent, "JADE_LIZARD", "BANKNIFTY", true, NOW));
    }

    @Test
    void otherUnderlyingOtherStrategyOrOldEntryIsAllowed() {
        var recent = List.of(p("JADE_LIZARD", "BANKNIFTY", "PAPER", 1, "OPEN"),
                             p("CONDOR_SPREAD", "NIFTY", "PAPER", 16, "CLOSED"));
        assertFalse(EntryCooldown.blocked(recent, "JADE_LIZARD", "NIFTY", true, NOW));
        assertFalse(EntryCooldown.blocked(recent, "BROKEN_WING_BUTTERFLY", "BANKNIFTY", true, NOW));
        assertFalse(EntryCooldown.blocked(recent, "CONDOR_SPREAD", "NIFTY", true, NOW));
    }

    @Test
    void closedRecentEntryStillCountsButVoidAndOtherSideDoNot() {
        assertTrue(EntryCooldown.blocked(List.of(p("CONDOR", "NIFTY", "PAPER", 5, "CLOSED")), "CONDOR_SPREAD", "NIFTY", true, NOW));
        assertFalse(EntryCooldown.blocked(List.of(p("CONDOR_SPREAD", "NIFTY", "PAPER", 5, "VOID")), "CONDOR_SPREAD", "NIFTY", true, NOW));
        assertFalse(EntryCooldown.blocked(List.of(p("CONDOR_SPREAD", "NIFTY", "MOTILALOSWAL", 5, "OPEN")), "CONDOR_SPREAD", "NIFTY", true, NOW));
    }
}
