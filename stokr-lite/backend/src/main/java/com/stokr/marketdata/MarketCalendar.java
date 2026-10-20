package com.stokr.marketdata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for "is the NSE F&O market trading right now?".
 *
 * Trading day = Mon–Fri, not an NSE holiday built in below, and not listed in
 * {@code stokr.market.holidays} (comma-separated yyyy-MM-dd, env STOKR_MARKET_HOLIDAYS,
 * for extra closures). Session = 09:15–15:30 IST.
 *
 * Static accessors are deliberate: several callers are plain helpers/records that are
 * not Spring beans. The component only loads the configured holiday list at startup.
 */
@Slf4j
@Component
public class MarketCalendar {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final LocalTime OPEN = LocalTime.of(9, 15);
    public static final LocalTime CLOSE = LocalTime.of(15, 30);

    private static final Set<LocalDate> HOLIDAYS = ConcurrentHashMap.newKeySet();

    /**
     * NSE equity/F&O weekday trading holidays. Built in so a missing env var can't turn a
     * holiday into a trading day (that happened on Dussehra, 2026-10-20). Update yearly
     * from the NSE holiday circular.
     */
    private static final Set<LocalDate> NSE_HOLIDAYS = Set.of(
        LocalDate.parse("2026-01-26"), // Republic Day
        LocalDate.parse("2026-03-03"), // Holi
        LocalDate.parse("2026-03-26"), // Shri Ram Navami
        LocalDate.parse("2026-03-31"), // Shri Mahavir Jayanti
        LocalDate.parse("2026-04-03"), // Good Friday
        LocalDate.parse("2026-04-14"), // Dr. Baba Saheb Ambedkar Jayanti
        LocalDate.parse("2026-05-01"), // Maharashtra Day
        LocalDate.parse("2026-05-28"), // Bakri Id
        LocalDate.parse("2026-06-26"), // Muharram
        LocalDate.parse("2026-09-14"), // Ganesh Chaturthi
        LocalDate.parse("2026-10-02"), // Mahatma Gandhi Jayanti
        LocalDate.parse("2026-10-20"), // Dussehra
        LocalDate.parse("2026-11-10"), // Diwali Balipratipada
        LocalDate.parse("2026-11-24"), // Guru Nanak Jayanti
        LocalDate.parse("2026-12-25")  // Christmas
    );

    public MarketCalendar(@Value("${stokr.market.holidays:}") String holidays) {
        setHolidays(holidays);
    }

    /** Replaces the holiday list; blank or malformed entries are skipped with a warning. */
    public static void setHolidays(String csv) {
        HOLIDAYS.clear();
        if (csv == null || csv.isBlank()) return;
        for (String part : csv.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            try {
                HOLIDAYS.add(LocalDate.parse(s));
            } catch (Exception e) {
                log.warn("Ignoring invalid market holiday '{}'", s);
            }
        }
        log.info("Market calendar loaded {} holiday(s)", HOLIDAYS.size());
    }

    public static boolean isTradingDay(LocalDate date) {
        DayOfWeek d = date.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY
                && !NSE_HOLIDAYS.contains(date) && !HOLIDAYS.contains(date);
    }

    public static boolean isTradingDayToday() {
        return isTradingDay(LocalDate.now(IST));
    }

    /**
     * Counts how many actual trading days have elapsed strictly between startDate and endDate
     * (excluding startDate, including endDate if it is a trading day).
     * Example: Entry on Friday, Checked on Monday -> 1 trading day (Monday).
     * Example: Entry on Tuesday, Checked on Thursday -> 2 trading days (Wednesday, Thursday).
     */
    public static int getTradingDaysElapsed(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || !startDate.isBefore(endDate)) return 0;
        int count = 0;
        LocalDate curr = startDate.plusDays(1);
        while (!curr.isAfter(endDate)) {
            if (isTradingDay(curr)) {
                count++;
            }
            curr = curr.plusDays(1);
        }
        return count;
    }

    /** True when {@code at} falls on a trading day between 09:15 and 15:30 IST (inclusive). */
    public static boolean isMarketOpen(ZonedDateTime at) {
        ZonedDateTime ist = at.withZoneSameInstant(IST);
        LocalTime t = ist.toLocalTime();
        return isTradingDay(ist.toLocalDate()) && !t.isBefore(OPEN) && !t.isAfter(CLOSE);
    }

    public static boolean isMarketOpenNow() {
        return isMarketOpen(ZonedDateTime.now(IST));
    }

    /** True on a trading day within [from, to) IST — for narrower windows such as auto-entry. */
    public static boolean isWithin(ZonedDateTime at, LocalTime from, LocalTime to) {
        ZonedDateTime ist = at.withZoneSameInstant(IST);
        LocalTime t = ist.toLocalTime();
        return isTradingDay(ist.toLocalDate()) && !t.isBefore(from) && t.isBefore(to);
    }
}
