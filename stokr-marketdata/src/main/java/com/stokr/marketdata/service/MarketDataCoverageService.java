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
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MarketDataCoverageService {

    private final MarketDataCoverageRepository coverageRepository;
    private final MarketdataCandleRepository candleRepository;
    private final CandleFinalizationService candleFinalizationService;

    @Value("${stokr.strategy.readiness.stale-seconds:600}")
    private long staleSeconds;
    @Value("${stokr.market.coverage.authority.revalidate-seconds:180}")
    private long authorityRevalidateSeconds;

    @Transactional
    public CoverageAssessment validateAndUpsert(String symbol, String timeframe, Instant from, Instant to) {
        List<MarketdataCandle> bars =
                candleRepository.findBySymbolInAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(symbolCandidates(symbol), timeframe, from, to);
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
        boolean replayReady = "READY_FOR_BACKTEST".equals(replayReadiness);
        boolean scannerReady = "READY_FOR_SCANNERS".equals(scannerReadiness);
        boolean partialCoverage = "PARTIAL".equals(completeness) || "GAPS_PRESENT".equals(completeness);

        MarketDataCoverage row = coverageRepository.findBySymbolAndTimeframeAndDeletedFalse(symbol, timeframe).orElseGet(MarketDataCoverage::new);
        row.setSymbol(symbol);
        row.setTimeframe(timeframe);
        row.setCoveredFrom(from);
        row.setCoveredTo(to);
        row.setCoverageStart(from);
        row.setCoverageEnd(to);
        row.setLatestCandleAt(latest);
        row.setFreshnessAgeSeconds(lag == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, lag));
        row.setCompleteness(completeness);
        row.setFreshness(freshness);
        row.setStaleState(freshness);
        row.setGapsPresent(!gaps.isEmpty());
        row.setGapCount(gaps.size());
        row.setReplayReadiness(replayReadiness);
        row.setScannerReadiness(scannerReadiness);
        row.setReplayReady(replayReady);
        row.setScannerReady(scannerReady);
        row.setPartialCoverage(partialCoverage);
        row.setCompletionPct(completionPct);
        row.setLastValidationAt(Instant.now());
        row.setValidatedRangeStart(from);
        row.setValidatedRangeEnd(to);
        row.setNote("expected=" + expected + " actual=" + actual + " lagSec=" + (lag == Long.MAX_VALUE ? -1 : lag));
        coverageRepository.save(row);

        return new CoverageAssessment(completeness, freshness, replayReadiness, scannerReadiness, !gaps.isEmpty(), gaps.size(), expected, actual, latest);
    }

    @Transactional(readOnly = true)
    public boolean isRangeAlreadyReady(String symbol, String timeframe, Instant from, Instant to) {
        return assessReadiness(symbol, timeframe, from, to, "REPLAY", true).ready();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentCoverage() {
        return coverageRepository.findTop200ByDeletedFalseOrderByUpdatedAtDesc().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", c.getSymbol());
            m.put("timeframe", c.getTimeframe());
            m.put("coveredFrom", c.getCoveredFrom() != null ? c.getCoveredFrom().toString() : null);
            m.put("coveredTo", c.getCoveredTo() != null ? c.getCoveredTo().toString() : null);
            m.put("coverageStart", c.getCoverageStart() != null ? c.getCoverageStart().toString() : null);
            m.put("coverageEnd", c.getCoverageEnd() != null ? c.getCoverageEnd().toString() : null);
            m.put("latestCandleAt", c.getLatestCandleAt() != null ? c.getLatestCandleAt().toString() : null);
            m.put("freshnessAgeSeconds", c.getFreshnessAgeSeconds());
            m.put("completeness", c.getCompleteness());
            m.put("freshness", c.getFreshness());
            m.put("staleState", c.getStaleState());
            m.put("replayReadiness", c.getReplayReadiness());
            m.put("scannerReadiness", c.getScannerReadiness());
            m.put("replayReady", c.isReplayReady());
            m.put("scannerReady", c.isScannerReady());
            m.put("partialCoverage", c.isPartialCoverage());
            m.put("gapsPresent", c.isGapsPresent());
            m.put("gapCount", c.getGapCount());
            m.put("completionPct", c.getCompletionPct());
            m.put("validatedAt", c.getLastValidationAt() != null ? c.getLastValidationAt().toString() : null);
            m.put("validatedRangeStart", c.getValidatedRangeStart() != null ? c.getValidatedRangeStart().toString() : null);
            m.put("validatedRangeEnd", c.getValidatedRangeEnd() != null ? c.getValidatedRangeEnd().toString() : null);
            return m;
        }).toList();
    }

    @Transactional
    public AuthorityAssessment assessReadiness(String symbol, String timeframe, Instant from, Instant to, String useCase, boolean autoRefresh) {
        String tf = timeframe == null || timeframe.isBlank() ? "1m" : timeframe.trim();
        MarketDataCoverage row = findCoverageRow(symbol, tf);
        if (autoRefresh && needsRevalidation(row, from, to)) {
            validateAndUpsert(symbol, tf, from, to);
            row = findCoverageRow(symbol, tf);
        }
        if (row == null) {
            return new AuthorityAssessment(symbol, tf, useCase, false, "NO_DATA", "Coverage row missing", null, null, null, null);
        }
        return toAuthorityAssessment(row, from, to, useCase);
    }

    private boolean needsRevalidation(MarketDataCoverage row, Instant from, Instant to) {
        if (row == null || row.getLastValidationAt() == null) {
            return true;
        }
        if (Duration.between(row.getLastValidationAt(), Instant.now()).getSeconds() > authorityRevalidateSeconds) {
            return true;
        }
        if (row.getValidatedRangeStart() == null || row.getValidatedRangeEnd() == null) {
            return true;
        }
        return row.getValidatedRangeStart().isAfter(from) || row.getValidatedRangeEnd().isBefore(to);
    }

    private AuthorityAssessment toAuthorityAssessment(MarketDataCoverage row, Instant from, Instant to, String useCase) {
        if ("NOT_BACKFILLED".equalsIgnoreCase(row.getCompleteness())) {
            return new AuthorityAssessment(row.getSymbol(), row.getTimeframe(), useCase, false, "NO_DATA", "Not backfilled", row.getLatestCandleAt(), row.getFreshnessAgeSeconds(), row.getCoverageStart(), row.getCoverageEnd());
        }
        if (row.getCoverageStart() == null || row.getCoverageEnd() == null || row.getCoverageStart().isAfter(from) || row.getCoverageEnd().isBefore(to)) {
            return new AuthorityAssessment(row.getSymbol(), row.getTimeframe(), useCase, false, "INCOMPLETE_RANGE", "Requested range not fully covered", row.getLatestCandleAt(), row.getFreshnessAgeSeconds(), row.getCoverageStart(), row.getCoverageEnd());
        }
        if (row.isGapsPresent() || "GAPS_PRESENT".equalsIgnoreCase(row.getCompleteness())) {
            return new AuthorityAssessment(row.getSymbol(), row.getTimeframe(), useCase, false, "GAPS_PRESENT", "Coverage contains continuity gaps", row.getLatestCandleAt(), row.getFreshnessAgeSeconds(), row.getCoverageStart(), row.getCoverageEnd());
        }
        if ("SCANNER".equalsIgnoreCase(useCase) && !"READY_FOR_SCANNERS".equalsIgnoreCase(row.getScannerReadiness())) {
            return new AuthorityAssessment(row.getSymbol(), row.getTimeframe(), useCase, false, normalizeState(row.getScannerReadiness()), "Scanner readiness not satisfied", row.getLatestCandleAt(), row.getFreshnessAgeSeconds(), row.getCoverageStart(), row.getCoverageEnd());
        }
        if ("REPLAY".equalsIgnoreCase(useCase) && !"READY_FOR_BACKTEST".equalsIgnoreCase(row.getReplayReadiness())) {
            return new AuthorityAssessment(row.getSymbol(), row.getTimeframe(), useCase, false, normalizeState(row.getReplayReadiness()), "Replay readiness not satisfied", row.getLatestCandleAt(), row.getFreshnessAgeSeconds(), row.getCoverageStart(), row.getCoverageEnd());
        }
        if ("STALE".equalsIgnoreCase(row.getFreshness()) && !"REPLAY".equalsIgnoreCase(useCase)) {
            return new AuthorityAssessment(row.getSymbol(), row.getTimeframe(), useCase, false, "STALE", "Freshness threshold exceeded", row.getLatestCandleAt(), row.getFreshnessAgeSeconds(), row.getCoverageStart(), row.getCoverageEnd());
        }
        if (row.isPartialCoverage() || "PARTIAL".equalsIgnoreCase(row.getCompleteness())) {
            return new AuthorityAssessment(row.getSymbol(), row.getTimeframe(), useCase, false, "PARTIAL", "Coverage is partial", row.getLatestCandleAt(), row.getFreshnessAgeSeconds(), row.getCoverageStart(), row.getCoverageEnd());
        }
        return new AuthorityAssessment(row.getSymbol(), row.getTimeframe(), useCase, true, "READY", "Coverage authority satisfied", row.getLatestCandleAt(), row.getFreshnessAgeSeconds(), row.getCoverageStart(), row.getCoverageEnd());
    }

    private static String normalizeState(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        if ("READY_FOR_BACKTEST".equals(v) || "READY_FOR_SCANNERS".equals(v)) {
            return "READY";
        }
        return v;
    }

    private MarketDataCoverage findCoverageRow(String symbol, String timeframe) {
        for (String s : symbolCandidates(symbol)) {
            MarketDataCoverage row = coverageRepository.findBySymbolAndTimeframeAndDeletedFalse(s, timeframe).orElse(null);
            if (row != null) {
                return row;
            }
        }
        return null;
    }

    private List<String> symbolCandidates(String symbol) {
        if (symbol == null) {
            return List.of();
        }
        String raw = symbol.trim();
        if (raw.isBlank()) {
            return List.of();
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        out.add(raw);
        out.add(upper);
        switch (upper) {
            case "NIFTY_FUT", "NIFTY" -> {
                out.add("NIFTY 50");
                out.add("NIFTY_50");
            }
            case "BANKNIFTY_FUT", "BANKNIFTY" -> {
                out.add("NIFTY BANK");
                out.add("NIFTY_BANK");
                out.add("BANK NIFTY");
            }
            case "FINNIFTY_FUT", "FINNIFTY" -> {
                out.add("NIFTY FIN SERVICE");
                out.add("NIFTY_FIN_SERVICE");
            }
            default -> {
                if (upper.endsWith("_FUT")) {
                    out.add(upper.substring(0, upper.length() - 4));
                }
                if (upper.contains(" ")) {
                    out.add(upper.replace(' ', '_'));
                }
                if (upper.contains("_")) {
                    out.add(upper.replace('_', ' '));
                }
            }
        }
        return List.copyOf(out);
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

    public record AuthorityAssessment(
            String symbol,
            String timeframe,
            String useCase,
            boolean ready,
            String state,
            String detail,
            Instant latestCandleAt,
            Long freshnessAgeSeconds,
            Instant coverageStart,
            Instant coverageEnd
    ) {
    }
}
