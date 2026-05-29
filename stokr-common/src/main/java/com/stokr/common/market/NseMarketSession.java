package com.stokr.common.market;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Canonical NSE cash session window for UI and scanner gates (IST).
 * Aligns with {@code IntradayReadinessService} — regular session 09:15–15:30 inclusive.
 */
public final class NseMarketSession {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SESSION_START = LocalTime.of(9, 15);
    private static final LocalTime SESSION_END = LocalTime.of(15, 30);

    public enum SessionState {
        WEEKEND,
        PRE_MARKET,
        MARKET_OPEN,
        POST_MARKET
    }

    private NseMarketSession() {
    }

    public static SessionState sessionState(Instant at) {
        ZonedDateTime z = at.atZone(IST);
        DayOfWeek dow = z.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return SessionState.WEEKEND;
        }
        LocalTime t = z.toLocalTime();
        if (t.isBefore(SESSION_START)) {
            return SessionState.PRE_MARKET;
        }
        if (!t.isAfter(SESSION_END)) {
            return SessionState.MARKET_OPEN;
        }
        return SessionState.POST_MARKET;
    }

    public static SessionState sessionStateNow() {
        return sessionState(Instant.now());
    }

    /** True during NSE regular cash session on weekdays (09:15–15:30 IST). */
    public static boolean isRegularSessionOpen(Instant at) {
        return sessionState(at) == SessionState.MARKET_OPEN;
    }

    public static boolean isRegularSessionOpen() {
        return isRegularSessionOpen(Instant.now());
    }
}
