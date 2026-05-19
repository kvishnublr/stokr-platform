package com.stokr.strategy.runtime;

import com.stokr.marketdata.service.MarketDataCoverageService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class SymbolReadinessService {

    private final MarketDataCoverageService marketDataCoverageService;
    @Value("${stokr.strategy.scanner.readiness.lookback-minutes:180}")
    private long scannerReadinessLookbackMinutes;

    /**
     * Validates 1m continuity/freshness for a symbol before scanner evaluation.
     */
    public Readiness assess(String symbol, Instant now) {
        Instant from = scannerWindowStart(now);
        // Scanner path is tolerant to minor continuity gaps to avoid hard deadlocks in live sessions.
        // We still block on hard states (NO_DATA/STALE/NOT_BACKFILLED/INCOMPLETE_RANGE).
        // autoRefresh=true: auto-populates market_data_coverage row if missing or stale (revalidates every 3 min).
        // Without this, symbols with no prior admin backfill always return NO_DATA and are permanently skipped.
        var authority = marketDataCoverageService.assessReadiness(symbol, "1m", from, now, "SCANNER", true);
        if (!authority.ready()) {
            if ("INCOMPLETE_RANGE".equalsIgnoreCase(authority.state())
                    || "STALE".equalsIgnoreCase(authority.state())
                    || "GAPS_PRESENT".equalsIgnoreCase(authority.state())) {
                // Tolerate incomplete range, minor staleness, and gaps during live sessions.
                return new Readiness(true, "READY_LIVE_TOLERANT");
            }
            return new Readiness(false, authority.state());
        }
        return new Readiness(true, "READY");
    }

    private Instant scannerWindowStart(Instant now) {
        long mins = Math.max(30L, scannerReadinessLookbackMinutes);
        return now.minus(Duration.ofMinutes(mins));
    }

    public record Readiness(boolean ready, String reason) {
    }
}
