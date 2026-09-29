package com.stokr.marketdata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class MarketCalendarTest {

    private static ZonedDateTime ist(String date, int h, int m) {
        return LocalDate.parse(date).atTime(h, m).atZone(MarketCalendar.IST);
    }

    @AfterEach
    void clearHolidays() {
        MarketCalendar.setHolidays("");
    }

    @Test
    void weekendsAreClosedEvenDuringSessionHours() {
        assertFalse(MarketCalendar.isMarketOpen(ist("2026-09-26", 11, 0))); // Saturday
        assertFalse(MarketCalendar.isMarketOpen(ist("2026-09-27", 11, 0))); // Sunday
    }

    @Test
    void weekdaySessionBoundariesAreInclusive() {
        assertFalse(MarketCalendar.isMarketOpen(ist("2026-09-28", 9, 14)));
        assertTrue(MarketCalendar.isMarketOpen(ist("2026-09-28", 9, 15)));
        assertTrue(MarketCalendar.isMarketOpen(ist("2026-09-28", 15, 30)));
        assertFalse(MarketCalendar.isMarketOpen(ist("2026-09-28", 15, 31)));
    }

    @Test
    void configuredHolidaysAreClosedAndBadEntriesIgnored() {
        MarketCalendar.setHolidays("2026-10-02, not-a-date ,");
        assertFalse(MarketCalendar.isTradingDay(LocalDate.parse("2026-10-02")));
        assertFalse(MarketCalendar.isMarketOpen(ist("2026-10-02", 11, 0)));
        assertTrue(MarketCalendar.isTradingDay(LocalDate.parse("2026-10-01")));
    }

    @Test
    void isWithinUsesHalfOpenWindowOnTradingDaysOnly() {
        LocalTime from = LocalTime.of(9, 20), to = LocalTime.of(15, 15);
        assertTrue(MarketCalendar.isWithin(ist("2026-09-28", 9, 20), from, to));
        assertFalse(MarketCalendar.isWithin(ist("2026-09-28", 15, 15), from, to));
        assertFalse(MarketCalendar.isWithin(ist("2026-09-26", 10, 0), from, to));
    }

    @Test
    void convertsOtherZonesToIst() {
        // 04:30 UTC = 10:00 IST on a Monday
        assertTrue(MarketCalendar.isMarketOpen(ZonedDateTime.parse("2026-09-28T04:30:00Z")));
    }
}
