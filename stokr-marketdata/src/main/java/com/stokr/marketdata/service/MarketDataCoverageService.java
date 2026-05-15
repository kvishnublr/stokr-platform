package com.stokr.marketdata.service;

import com.stokr.marketdata.domain.MarketDataCoverage;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.repository.MarketDataCoverageRepository;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MarketDataCoverageService {

    private final MarketDataCoverageRepository coverageRepository;
    private final MarketdataCandleRepository candleRepository;
    private final CandleFinalizationService candleFinalizationService;

    @Value("${stokr.strategy.readiness.stale-seconds:600}")
    private long staleSeconds;

    @Transactional
    public CoverageAssessment validateAndUpsert(String symbol, String timeframe, Instant from, Instant to) {
        List<MarketdataCandle> bars =
                candleRepository.findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(symbol, timeframe, from, to);
        long expected = expectedBars(timeframe, from, to);
        long actual = bars.size();
        Instant latest = bars.isEmpty() ? null : bars.get(bars.size() - 1).getOpenTime();
        List<CandleFinalizationService.CandleGap> gaps = candleFinalizationService.detectGapsMarketHoursAware(symbol, timeframe, from, to);

        String completeness;
        if (actual == 0L) {
            completeness = "NOT_BACKFILLED";
        } else if (!gaps.isEmpty()) {
            completeness = "GAPS_PRESENT";
        } else if (expected > 0L && actual < expected) {
            completeness = "PARTIAL";
        } else {
            completeness = "READY";
        }
        long lag = latest == null ? Long.MAX_VALUE : Duration.between(latest, Instant.now()).getSeconds();
        String freshness = lag > staleSeconds ? "STALE" : "FRESH";
        String replayReadiness = switch (completeness) {
            case "READY" -> "READY_FOR_BACKTEST";
            case "NOT_BACKFILLED" -> "NOT_BACKFILLED";
            case "GAPS_PRESENT" -> "GAPS_PRESENT";
            default -> "INCOMPLETE_RANGE";
        };
        String scannerReadiness = ("READY".equals(completeness) && "FRESH".equals(freshness)) ? "READY_FOR_SCANNERS" : replayReadiness;
        BigDecimal completionPct = expected <= 0L ? BigDecimal.ZERO :
                BigDecimal.valueOf(actual).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(expected), 4, RoundingMode.HALF_UP);

        MarketDataCoverage row = coverageRepository.findBySymbolAndTimeframeAndDeletedFalse(symbol, timeframe).orElseGet(MarketDataCoverage::new);
        row.setSymbol(symbol);
        row.setTimeframe(timeframe);
        row.setCoveredFrom(from);
        row.setCoveredTo(to);
        row.setLatestCandleAt(latest);
        row.setCompleteness(completeness);
        row.setFreshness(freshness);
        row.setGapsPresent(!gaps.isEmpty());
        row.setGapCount(gaps.size());
        row.setReplayReadiness(replayReadiness);
        row.setScannerReadiness(scannerReadiness);
        row.setCompletionPct(completionPct);
        row.setLastValidationAt(Instant.now());
        row.setNote("expected=" + expected + " actual=" + actual + " lagSec=" + (lag == Long.MAX_VALUE ? -1 : lag));
        coverageRepository.save(row);

        return new CoverageAssessment(completeness, freshness, replayReadiness, scannerReadiness, !gaps.isEmpty(), gaps.size(), expected, actual, latest);
    }

    @Transactional(readOnly = true)
    public boolean isRangeAlreadyReady(String symbol, String timeframe, Instant from, Instant to) {
        MarketDataCoverage c = coverageRepository.findBySymbolAndTimeframeAndDeletedFalse(symbol, timeframe).orElse(null);
        if (c == null) {
            return false;
        }
        if (!"READY".equalsIgnoreCase(c.getCompleteness()) || !"READY_FOR_BACKTEST".equalsIgnoreCase(c.getReplayReadiness())) {
            return false;
        }
        return !c.isGapsPresent()
                && c.getCoveredFrom() != null
                && c.getCoveredTo() != null
                && !c.getCoveredFrom().isAfter(from)
                && !c.getCoveredTo().isBefore(to);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentCoverage() {
        return coverageRepository.findTop200ByDeletedFalseOrderByUpdatedAtDesc().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", c.getSymbol());
            m.put("timeframe", c.getTimeframe());
            m.put("coveredFrom", c.getCoveredFrom() != null ? c.getCoveredFrom().toString() : null);
            m.put("coveredTo", c.getCoveredTo() != null ? c.getCoveredTo().toString() : null);
            m.put("latestCandleAt", c.getLatestCandleAt() != null ? c.getLatestCandleAt().toString() : null);
            m.put("completeness", c.getCompleteness());
            m.put("freshness", c.getFreshness());
            m.put("replayReadiness", c.getReplayReadiness());
            m.put("scannerReadiness", c.getScannerReadiness());
            m.put("gapsPresent", c.isGapsPresent());
            m.put("gapCount", c.getGapCount());
            m.put("completionPct", c.getCompletionPct());
            return m;
        }).toList();
    }

    private long expectedBars(String timeframe, Instant from, Instant to) {
        String tf = timeframe == null ? "1m" : timeframe.trim().toLowerCase(Locale.ROOT);
        if ("1d".equals(tf)) {
            long days = Math.max(1L, Duration.between(from, to).toDays());
            return days;
        }
        long stepSeconds = switch (tf) {
            case "5m" -> 300L;
            case "15m" -> 900L;
            case "1h" -> 3600L;
            default -> 60L;
        };
        long sessionSeconds = Duration.ofHours(6).plusMinutes(15).getSeconds();
        long barsPerDay = Math.max(1L, sessionSeconds / stepSeconds);
        long days = Math.max(1L, Duration.between(from, to).toDays());
        return days * barsPerDay;
    }

    public record CoverageAssessment(
            String completeness,
            String freshness,
            String replayReadiness,
            String scannerReadiness,
            boolean gapsPresent,
            int gapCount,
            long expectedBars,
            long actualBars,
            Instant latestCandleAt
    ) {
    }
}

