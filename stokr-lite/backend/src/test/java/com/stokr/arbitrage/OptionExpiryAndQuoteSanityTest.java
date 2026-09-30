package com.stokr.arbitrage;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class OptionExpiryAndQuoteSanityTest {

    private static OptionChainService.OptionQuote q(double bid, double ask) {
        OptionChainService.OptionQuote x = new OptionChainService.OptionQuote();
        x.bid = bid;
        x.ask = ask;
        x.lastPrice = (bid + ask) / 2;
        return x;
    }

    @Test
    void nseIndicesExpireTuesdayBseThursday() {
        OptionChainService s = new OptionChainService(null);
        for (String u : new String[] {"NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"}) {
            assertEquals(DayOfWeek.TUESDAY, s.getExpiryDayForUnderlying(u), u);
        }
        assertEquals(DayOfWeek.THURSDAY, s.getExpiryDayForUnderlying("SENSEX"));
    }

    @Test
    void monthlyRollsToNextMonthTheDayAfterExpiry() {
        // 30 Sep 2026: September's contract expired on Tue 29 Sep; October's (Tue 27 Oct) is live.
        assertEquals(LocalDate.of(2026, 10, 27), OptionChainService.monthlyExpiry(LocalDate.of(2026, 9, 30), DayOfWeek.TUESDAY));
        assertEquals(LocalDate.of(2026, 9, 29), OptionChainService.monthlyExpiry(LocalDate.of(2026, 9, 28), DayOfWeek.TUESDAY));
    }

    @Test
    void weeklyIsNextTuesday() {
        assertEquals(LocalDate.of(2026, 10, 6), OptionChainService.weeklyExpiry(LocalDate.of(2026, 9, 30), DayOfWeek.TUESDAY));
    }

    @Test
    void outOfOrderStrikePricesAreRejected() {
        // Quotes the BANKNIFTY condors were entered on, 30 Sep 2026 (53500/53600/53700/53800 CE):
        // a lower-strike call cheaper than a higher-strike one cannot be a real market.
        assertFalse(OptionChainService.strikeOrdered("CE", q(842, 844), q(896, 897.6), q(924, 925.2), q(890, 890.8)));
        // A normal call chain falls with strike; a normal put chain rises.
        assertTrue(OptionChainService.strikeOrdered("CE", q(900, 902), q(830, 832), q(765, 767), q(700, 702)));
        assertTrue(OptionChainService.strikeOrdered("PE", q(300, 302), q(340, 342), q(385, 387)));
        assertFalse(OptionChainService.strikeOrdered("PE", q(385, 387), q(340, 342)));
        assertFalse(OptionChainService.strikeOrdered("CE", q(100, 101), null));
    }
}
