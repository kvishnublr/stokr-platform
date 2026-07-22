package com.stokr.broker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;

/**
 * NFO Option Symbol Resolver — converts strategy parameters to Zerodha
 * NFO option trading symbols.
 *
 * <p>Zerodha NFO format: {@code NFO:NIFTY24JUL24500CE}
 * <ul>
 *   <li>Exchange: NFO</li>
 *   <li>Underlying: NIFTY</li>
 *   <li>Expiry: YY + MON (3 chars uppercase) + DD (e.g., 24JUL18)</li>
 *   <li>Strike: integer (e.g., 24500)</li>
 *   <li>Type: CE (call) or PE (put)</li>
 * </ul>
 *
 * <p>NIFTY weekly expirations: every Thursday.
 * If Thursday is a holiday, expiry is the previous trading day.
 *
 * <p>Zerodha NFO URLs:
 * <ul>
 *   <li>Place order: {@code POST /api/orders} with exchange=NFO</li>
 *   <li>Option chain: {@code GET /api/mf/orders} or {@code GET /api/quote?i=NFO:NIFTY24JUL24500CE}</li>
 *   <li>LTP: {@code GET /api/ltp?i=NFO:NIFTY24JUL24500CE}</li>
 * </ul>
 */
@Slf4j
@Component
public class NfoOptionResolver {

    private static final DateTimeFormatter MON_FORMAT = DateTimeFormatter.ofPattern("MMM").localizedBy(java.util.Locale.ENGLISH);

    /**
     * Build a complete NFO trading symbol.
     *
     * @param underlying   NIFTY, BANKNIFTY, FINNIFTY
     * @param expiryDate   option expiry date (Thursday)
     * @param strike       strike price
     * @param optionType   "CE" or "PE"
     * @return "NFO:NIFTY24JUL24500CE"
     */
    public static String buildSymbol(String underlying, LocalDate expiryDate,
                                       long strike, String optionType) {
        String mon = expiryDate.getMonth().name().substring(0, 3);
        String yy = String.valueOf(expiryDate.getYear()).substring(2);
        String dd = String.format("%02d", expiryDate.getDayOfMonth());

        return String.format("NFO:%s%s%s%s%d%s",
            underlying.toUpperCase(), yy, mon, dd, strike, optionType.toUpperCase());
    }

    /**
     * Get the next weekly expiry (Thursday).
     */
    public static LocalDate nextThursday() {
        return LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY));
    }

    /**
     * Get last Thursday — for the current week's expiry.
     */
    public static LocalDate currentThursday() {
        LocalDate today = LocalDate.now();
        // If today is Thu/Fri/Sat/Sun, current is today-or-last Thu
        if (today.getDayOfWeek().getValue() <= DayOfWeek.THURSDAY.getValue()) {
            return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.THURSDAY));
        }
        return today.with(TemporalAdjusters.previous(DayOfWeek.THURSDAY));
    }

    /**
     * Compute ATM strike from NIFTY spot.
     * NIFTY options have 50-point strike intervals.
     */
    public static long atmStrike(double niftySpot) {
        return Math.round(niftySpot / 50.0) * 50;
    }

    /**
     * Get Wednesday 3:15 PM (calendar spread exit time).
     */
    public static boolean isExitTime(java.time.LocalTime now) {
        int totalMin = now.getHour() * 60 + now.getMinute();
        return totalMin >= 15 * 60 + 10 && totalMin <= 15 * 60 + 20; // 3:10-3:20 PM
    }

    /**
     * Check if today is the exit day (Wednesday).
     */
    public static boolean isExitDay() {
        return LocalDate.now().getDayOfWeek() == DayOfWeek.WEDNESDAY;
    }
}
