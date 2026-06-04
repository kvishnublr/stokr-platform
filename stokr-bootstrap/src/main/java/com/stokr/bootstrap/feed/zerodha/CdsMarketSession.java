package com.stokr.bootstrap.feed.zerodha;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Set;

/** NSE CDS (currency derivatives) session window in IST. */
public final class CdsMarketSession {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime CDS_OPEN = LocalTime.of(9, 0);
    private static final LocalTime CDS_CLOSE = LocalTime.of(17, 0);

    private static final Set<String> MAJOR_PAIRS = Set.of("USDINR", "EURINR", "GBPINR", "JPYINR");

    private CdsMarketSession() {
    }

    public static boolean isCdsSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String upper = symbol.trim().toUpperCase(Locale.ROOT);
        for (String pair : MAJOR_PAIRS) {
            if (upper.equals(pair) || upper.startsWith(pair)) {
                return true;
            }
        }
        return false;
    }

    public static Instant sessionStart(LocalDate sessionDate, String symbol) {
        LocalTime open = isCdsSymbol(symbol) ? CDS_OPEN : LocalTime.of(9, 15);
        return sessionDate.atTime(open).atZone(IST).toInstant();
    }

    public static boolean isCdsMarketHours(Instant now) {
        ZonedDateTime zdt = now.atZone(IST);
        if (zdt.getDayOfWeek().getValue() >= 6) {
            return false;
        }
        LocalTime t = zdt.toLocalTime();
        return !t.isBefore(CDS_OPEN) && !t.isAfter(CDS_CLOSE);
    }

    public static boolean isWithinSession(Instant now, String symbol) {
        ZonedDateTime zdt = now.atZone(IST);
        if (zdt.getDayOfWeek().getValue() >= 6) {
            return false;
        }
        LocalTime t = zdt.toLocalTime();
        if (isCdsSymbol(symbol)) {
            return !t.isBefore(CDS_OPEN) && !t.isAfter(CDS_CLOSE);
        }
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }
}
