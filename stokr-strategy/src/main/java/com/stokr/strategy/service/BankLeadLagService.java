package com.stokr.strategy.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EXACT PORT of Python LeadLagDetector from intraday_engine/core.py.
 *
 * Python logic:
 *   - Tracks 12 bank stocks (BANK_SYMBOLS from config.py)
 *   - For each symbol, computes % change over BREADTH_WINDOW_SEC (30s → last 1 bar in 1m)
 *   - If |change| >= LL_PRICE_PCT_MOVE (0.05%), counts as bull/bear
 *   - Direction = BULL if bull >= bear else BEAR
 *   - Breadth = max(bull, bear) / len(BANK_SYMBOLS)
 *   - Lag detection: checks if banks_group moved before BNF_SYMBOL
 *   - Raw score = breadth * (1.0 if lag_ok else 0.25)
 *
 * Java adaptation:
 *   - Uses 1-minute candles instead of tick-by-tick data
 *   - Queries last N bars for each bank stock from MarketDataQueryService
 *   - Computes breadth from close-to-close changes
 *   - Lead-lag approximated from which symbols moved first
 *
 * Caches results for 30 seconds to avoid DB hammering.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankLeadLagService {

    // Python config.py: BANK_SYMBOLS (12 bank stocks)
    private static final List<String> BANK_SYMBOLS = List.of(
        "HDFCBANK", "ICICIBANK", "KOTAKBANK", "AXISBANK",
        "SBIN", "INDUSINDBK", "BANKBARODA", "PNB",
        "FEDERALBNK", "BANDHANBNK", "IDFCFIRSTB", "RBLBANK"
    );

    // Python config.py constants
    private static final double LL_PRICE_PCT_MOVE = 0.05;   // 0.05%
    private static final int LL_BREADTH_MIN = 6;
    private static final double LL_LAG_MIN_SEC = 0.5;
    private static final double LL_LAG_MAX_SEC = 8.0;
    private static final int BREADTH_WINDOW_BARS = 5;   // ~5 minutes of 1m bars for breadth calc

    private static final long CACHE_TTL_MS = 30_000;  // 30 second cache

    private final MarketDataQueryService marketDataQueryService;
    private final ConcurrentHashMap<String, CachedResult> cache = new ConcurrentHashMap<>();

    /**
     * Compute lead-lag score, direction, and breadth for the current market state.
     * Matches Python LeadLagDetector.score() output.
     *
     * @param asOf evaluation time (null = now)
     * @return LeadLagResult with score(0-1), direction(BULL/BEAR), breadth(0-1)
     */
    public LeadLagResult compute(Instant asOf) {
        Instant now = asOf != null ? asOf : Instant.now();
        String cacheKey = "global";
        CachedResult cached = cache.get(cacheKey);
        if (cached != null && Duration.between(cached.timestamp, now).toMillis() < CACHE_TTL_MS) {
            return cached.result;
        }

        int bull = 0;
        int bear = 0;
        int movedCount = 0;
        Instant earliestMoveTime = null;

        for (String symbol : BANK_SYMBOLS) {
            try {
                List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(symbol, "1m", BREADTH_WINDOW_BARS + 5);
                if (bars.size() < 3) continue;

                int n = bars.size();
                int lookback = Math.min(BREADTH_WINDOW_BARS, n - 1);
                BigDecimal recentClose = bars.get(n - 1).getClosePrice();
                BigDecimal olderClose = bars.get(n - 1 - lookback).getClosePrice();

                if (recentClose == null || olderClose == null || olderClose.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                double pctChange = recentClose.subtract(olderClose)
                    .divide(olderClose, 6, java.math.RoundingMode.HALF_UP)
                    .doubleValue() * 100.0;

                if (pctChange > LL_PRICE_PCT_MOVE) bull++;
                if (pctChange < -LL_PRICE_PCT_MOVE) bear++;

                if (Math.abs(pctChange) >= LL_PRICE_PCT_MOVE) {
                    movedCount++;
                    Instant moveTime = bars.get(n - 1).getOpenTime();
                    if (earliestMoveTime == null || (moveTime != null && moveTime.isBefore(earliestMoveTime))) {
                        earliestMoveTime = moveTime;
                    }
                }
            } catch (Exception e) {
                log.trace("bank_lead_lag.skip symbol={} reason={}", symbol, e.getMessage());
            }
        }

        String direction = bull >= bear ? "BULL" : "BEAR";
        int aligned = Math.max(bull, bear);
        double breadth = (double) aligned / Math.max(BANK_SYMBOLS.size(), 1);

        boolean lagOk = movedCount >= LL_BREADTH_MIN;

        if (lagOk && earliestMoveTime != null) {
            try {
                List<MarketdataCandle> bnfBars = marketDataQueryService.lastBarsAsc("BANKNIFTY", "1m", BREADTH_WINDOW_BARS + 5);
                if (bnfBars.size() >= 3) {
                    int bn = bnfBars.size();
                    BigDecimal bnfRecent = bnfBars.get(bn - 1).getClosePrice();
                    BigDecimal bnfOlder = bnfBars.get(bn - 1 - Math.min(BREADTH_WINDOW_BARS, bn - 1)).getClosePrice();
                    if (bnfRecent != null && bnfOlder != null && bnfOlder.compareTo(BigDecimal.ZERO) > 0) {
                        double bnfChg = bnfRecent.subtract(bnfOlder)
                            .divide(bnfOlder, 6, java.math.RoundingMode.HALF_UP)
                            .doubleValue() * 100.0;
                        if (Math.abs(bnfChg) >= LL_PRICE_PCT_MOVE) {
                            Instant bnfMoveTime = bnfBars.get(bn - 1).getOpenTime();
                            if (bnfMoveTime != null) {
                                double lagSec = Duration.between(earliestMoveTime, bnfMoveTime).toMillis() / 1000.0;
                                lagOk = lagSec >= LL_LAG_MIN_SEC && lagSec <= LL_LAG_MAX_SEC;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.trace("bank_lead_lag.bnf_skip reason={}", e.getMessage());
            }
        }

        double raw = breadth * (lagOk ? 1.0 : 0.25);
        double score = Math.round(Math.min(1.0, Math.max(0.0, raw)) * 10000.0) / 10000.0;
        breadth = Math.round(breadth * 10000.0) / 10000.0;

        LeadLagResult result = new LeadLagResult(score, direction, breadth, bull, bear, movedCount, lagOk);
        cache.put(cacheKey, new CachedResult(now, result));

        log.debug("bank_lead_lag.computed score={} dir={} breadth={} bull={} bear={} moved={} lag_ok={}",
            score, direction, breadth, bull, bear, movedCount, lagOk);

        return result;
    }

    public record LeadLagResult(
        double score, String direction, double breadth,
        int bullCount, int bearCount, int movedCount, boolean lagOk
    ) {}

    private record CachedResult(Instant timestamp, LeadLagResult result) {}
}
