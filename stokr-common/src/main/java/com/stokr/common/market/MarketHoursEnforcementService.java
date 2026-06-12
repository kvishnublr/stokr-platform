package com.stokr.common.market;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Enforces market hours restrictions on order placement.
 * Blocks all new orders after market close times (3:00 PM NSE, 11:55 PM MCX).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketHoursEnforcementService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MarketHoursEnforcementService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime NSE_MARKET_CLOSE = LocalTime.of(15, 0); // 3:00 PM
    private static final LocalTime MCX_MARKET_CLOSE = LocalTime.of(23, 55); // 11:55 PM
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15); // 9:15 AM

    /**
     * Check if order submission is allowed based on market hours.
     * Returns true if order can be submitted, false if market is closed.
     */
    public boolean isOrderSubmissionAllowed(String segment) {
        if (!isMarketOpen(segment)) {
            log.warn("market_hours.blocked segment={} reason=market_closed", segment);
            return false;
        }
        return true;
    }

    /**
     * Check if market is currently open for the given segment.
     */
    public boolean isMarketOpen(String segment) {
        ZonedDateTime now = Instant.now().atZone(IST);
        LocalTime currentTime = now.toLocalTime();
        int dayOfWeek = now.getDayOfWeek().getValue();

        // Market closed on weekends (Saturday=6, Sunday=7)
        if (dayOfWeek >= 6) {
            return false;
        }

        // NSE hours: 9:15 AM - 3:00 PM
        if ("NSE".equalsIgnoreCase(segment)) {
            return currentTime.isAfter(MARKET_OPEN) && currentTime.isBefore(NSE_MARKET_CLOSE);
        }

        // MCX hours: 9:15 AM - 11:55 PM (next day)
        if ("MCX".equalsIgnoreCase(segment)) {
            return currentTime.isAfter(MARKET_OPEN) && currentTime.isBefore(MCX_MARKET_CLOSE);
        }

        // NCDEX (commodity): Similar to MCX
        if ("NCDEX".equalsIgnoreCase(segment)) {
            return currentTime.isAfter(MARKET_OPEN) && currentTime.isBefore(MCX_MARKET_CLOSE);
        }

        return true;
    }

    /**
     * Get the reason why market is closed (for logging)
     */
    public String getMarketClosureReason(String segment) {
        ZonedDateTime now = Instant.now().atZone(IST);
        LocalTime currentTime = now.toLocalTime();
        int dayOfWeek = now.getDayOfWeek().getValue();

        if (dayOfWeek >= 6) {
            return "Weekend market closure";
        }

        if ("NSE".equalsIgnoreCase(segment)) {
            if (currentTime.isBefore(MARKET_OPEN)) {
                return "NSE market not yet open (opens at 9:15 AM)";
            }
            if (currentTime.isAfter(NSE_MARKET_CLOSE)) {
                return "NSE market closed at 3:00 PM - no new orders allowed";
            }
        }

        if ("MCX".equalsIgnoreCase(segment) || "NCDEX".equalsIgnoreCase(segment)) {
            if (currentTime.isBefore(MARKET_OPEN)) {
                return "Market not yet open (opens at 9:15 AM)";
            }
            if (currentTime.isAfter(MCX_MARKET_CLOSE)) {
                return "Market closed at 11:55 PM";
            }
        }

        return "Market is closed";
    }
}
