package com.stokr.strategy.runtime;

import com.stokr.marketdata.service.MarketDataCoverageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
@RequiredArgsConstructor
public class SymbolReadinessService {

    private final MarketDataCoverageService marketDataCoverageService;

    /**
     * Validates 1m continuity/freshness for a symbol before scanner evaluation.
     */
    public Readiness assess(String symbol, Instant now) {
        Instant from = scannerWindowStart(now);
        // Scanner path is tolerant to minor continuity gaps to avoid hard deadlocks in live sessions.
        // We still block on hard states (NO_DATA/STALE/NOT_BACKFILLED/INCOMPLETE_RANGE).
        var authority = marketDataCoverageService.assessReadiness(symbol, "1m", from, now, "SCANNER", false);
        if (!authority.ready()) {
            return new Readiness(false, authority.state());
        }
        return new Readiness(true, "READY");
    }

    private Instant scannerWindowStart(Instant now) {
        ZonedDateTime z = now.atZone(ZoneId.of("Asia/Kolkata"));
        ZonedDateTime open = z.withHour(9).withMinute(15).withSecond(0).withNano(0);
        if (z.isBefore(open)) {
            open = open.minusDays(1);
        }
        while (open.getDayOfWeek().getValue() >= 6) {
            open = open.minusDays(1);
        }
        return open.toInstant();
    }

    public record Readiness(boolean ready, String reason) {
    }
}
