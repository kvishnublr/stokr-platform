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
 * Trading day = Mon–Fri and not listed in {@code stokr.market.holidays}
 * (comma-separated yyyy-MM-dd, env STOKR_MARKET_HOLIDAYS). Session = 09:15–15:30 IST.
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
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY && !HOLIDAYS.contains(date);
    }

    public static boolean isTradingDayToday() {
        return isTradingDay(LocalDate.now(IST));
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
