package com.stokr.intraday.service;

import com.stokr.common.market.NseMarketSession;
import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.stream.RealTimeSetupStream;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.domain.StrategyUniverseGroup;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.repository.StrategyUniverseGroupRepository;
import com.stokr.strategy.repository.StrategyUniverseSymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ranks live intraday market movers from candle data — NOT strategy universe scan order.
 * Cached briefly so ADV terminal responds fast.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LiveIntradayMoverService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration CACHE_TTL = Duration.ofSeconds(12);

    private final MarketDataQueryService marketDataQueryService;
    private final StrategyUniverseGroupRepository groupRepository;
    private final StrategyUniverseSymbolRepository symbolRepository;
    private final RealTimeSetupStream realTimeStream;

    @Value("${stokr.adv-dashboard.universe-group:NIFTY_100}")
    private String universeGroupKey;

    @Value("${stokr.adv-dashboard.mover-scan-limit:80}")
    private int moverScanLimit;

    private volatile List<Map<String, Object>> cachedRows = List.of();
    private volatile Instant cachedAt = Instant.EPOCH;

    /** Lightweight watch rows for instant ADV UI before full terminal sync. */
    public List<Map<String, Object>> liveWatchRows(int limit) {
        return cachedMovers(limit).stream()
                .map(m -> {
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("symbol", m.get("symbol"));
                    w.put("price", fmtPrice(m.get("ltp")));
                    w.put("changePct", String.valueOf(m.getOrDefault("changePct", "0")));
                    w.put("source", m.getOrDefault("source", "LIVE_MARKET"));
                    w.put("aiScore", m.getOrDefault("aiScore", 0));
                    return w;
                })
                .toList();
    }

    @Scheduled(fixedDelayString = "${stokr.adv-dashboard.mover-cache-ms:12000}")
    public void warmMoverCache() {
        refreshCacheIfStale();
    }

    public List<Map<String, Object>> liveScannerRows(int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CurrentSetup setup : realTimeStream.getRankingBoard()) {
            if (rows.size() >= limit) {
                break;
            }
            rows.add(fromRankingBoard(setup));
        }
        for (Map<String, Object> mover : cachedMovers(limit - rows.size())) {
            String sym = String.valueOf(mover.get("symbol"));
            if (rows.stream().anyMatch(r -> sym.equals(String.valueOf(r.get("symbol"))))) {
                continue;
            }
            rows.add(mover);
        }
        return rows;
    }

    private List<Map<String, Object>> cachedMovers(int limit) {
        refreshCacheIfStale();
        if (limit <= 0 || cachedRows.isEmpty()) {
            return List.of();
        }
        return cachedRows.stream().limit(limit).toList();
    }

    private void refreshCacheIfStale() {
        if (!cachedRows.isEmpty() && Duration.between(cachedAt, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return;
        }
        synchronized (this) {
            if (!cachedRows.isEmpty() && Duration.between(cachedAt, Instant.now()).compareTo(CACHE_TTL) < 0) {
                return;
            }
            List<Map<String, Object>> fresh = scanTopMovers();
            if (fresh.isEmpty() && !cachedRows.isEmpty() && NseMarketSession.isRegularSessionOpen()) {
                log.warn("live.movers.scan_empty keeping previous cache rows={}", cachedRows.size());
                cachedAt = Instant.now();
                return;
            }
            cachedRows = fresh;
            cachedAt = Instant.now();
        }
    }

    private List<Map<String, Object>> scanTopMovers() {
        List<String> symbols = resolveUniverseSymbols();
        List<MoverScore> scores = new ArrayList<>();
        LocalDate today = LocalDate.now(IST);

        for (String raw : symbols) {
            String sym = stripNse(raw);
            if (isIndexSymbol(sym)) {
                continue;
            }
            List<MarketdataCandle> bars = loadBars(sym);
            if (bars.size() < 3) {
                continue;
            }
            MoverScore score = scoreSymbol(sym, bars, today);
            if (score != null) {
                scores.add(score);
            }
        }

        scores.sort(Comparator
                .comparingDouble(MoverScore::activityScore).reversed()
                .thenComparing(Comparator.comparingDouble(MoverScore::absDayChange).reversed()));

        List<Map<String, Object>> out = new ArrayList<>();
        for (MoverScore s : scores.stream().limit(30).toList()) {
            out.add(toMoverRow(s));
        }
        log.debug("live.movers.scanned universe={} scored={} returned={}", symbols.size(), scores.size(), out.size());
        return out;
    }

    private List<MarketdataCandle> loadBars(String sym) {
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(sym, "1m", 90);
        if (bars.size() >= 3) {
            return bars;
        }
        return marketDataQueryService.lastBarsAsc(sym, "5m", 60);
    }

    private MoverScore scoreSymbol(String symbol, List<MarketdataCandle> bars, LocalDate today) {
        MarketdataCandle last = bars.get(bars.size() - 1);
        BigDecimal close = last.getClosePrice();
        if (close == null || close.signum() <= 0) {
            return null;
        }

        LocalDate sessionDay = resolveSessionDay(bars, today);
        BigDecimal sessionOpen = null;
        long volumeSum = 0;
        for (MarketdataCandle bar : bars) {
            if (bar.getOpenTime() == null) {
                continue;
            }
            LocalDate barDay = bar.getOpenTime().atZone(IST).toLocalDate();
            if (!barDay.equals(sessionDay)) {
                continue;
            }
            if (sessionOpen == null && bar.getOpenPrice() != null && bar.getOpenPrice().signum() > 0) {
                sessionOpen = bar.getOpenPrice();
            }
            if (bar.getVolume() != null) {
                volumeSum += bar.getVolume().longValue();
            }
        }
        if (sessionOpen == null) {
            sessionOpen = bars.get(0).getOpenPrice();
        }
        if (sessionOpen == null || sessionOpen.signum() == 0) {
            return null;
        }

        double dayChange = close.subtract(sessionOpen)
                .divide(sessionOpen, 6, RoundingMode.HALF_UP).doubleValue() * 100.0;

        int lookback = Math.min(15, bars.size() - 1);
        BigDecimal prev = bars.get(bars.size() - 1 - lookback).getClosePrice();
        double recentMom = prev != null && prev.signum() > 0
                ? close.subtract(prev).divide(prev, 6, RoundingMode.HALF_UP).doubleValue() * 100.0
                : 0.0;

        double volFactor = Math.min(3.0, Math.log10(Math.max(1, volumeSum)));
        double activity = Math.abs(dayChange) * 3.0 + Math.abs(recentMom) * 2.0 + volFactor;

        return new MoverScore(symbol, close, dayChange, recentMom, volumeSum, activity, volFactor);
    }

    private Map<String, Object> toMoverRow(MoverScore s) {
        Map<String, Object> row = new LinkedHashMap<>();
        int ai = (int) Math.min(92, Math.max(38, 42 + s.activityScore() * 4));
        String side = s.dayChangePct() >= 0 ? "BUY" : "SELL";
        String tag = Math.abs(s.dayChangePct()) >= 1.5 ? "HIGH_MOMENTUM"
                : s.volumeFactor() >= 2 ? "VOLUME_EXPANSION" : "ACTIVE";

        row.put("symbol", s.symbol());
        row.put("ltp", s.ltp());
        row.put("side", side);
        int buyPct = side.equals("BUY")
                ? Math.min(92, Math.max(45, 50 + (int) Math.abs(s.dayChangePct()) * 8))
                : Math.max(8, Math.min(55, 50 - (int) Math.abs(s.dayChangePct()) * 8));
        row.put("buyPct", buyPct);
        row.put("sellPct", 100 - buyPct);
        row.put("aiScore", ai);
        row.put("probability", ai);
        row.put("changePct", round2(s.dayChangePct()));
        row.put("momentumPct", round2(s.recentMomentumPct()));
        row.put("volume", s.volume());
        row.put("activityScore", round2(s.activityScore()));
        row.put("executionStatus", "INTELLIGENCE_ONLY");
        row.put("pipelineStage", "MARKET_SCAN");
        row.put("rejectionReason", null);
        row.put("rejectionCode", null);
        row.put("requestedMode", "—");
        row.put("effectiveMode", "—");
        row.put("qualityGate", "MARKET");
        row.put("riskGate", "N/A");
        row.put("cooldownSecRemaining", 0);
        row.put("lifecycle", List.of("DETECTED", "MARKET_SCAN"));
        row.put("setupType", tag);
        row.put("strategy", "LIVE_MARKET");
        row.put("status", "INTELLIGENCE_ONLY");
        row.put("displayStatus", "ACTIVE");
        row.put("source", "LIVE_MARKET");
        row.put("tradeQuality", ai >= 75 ? "A SETUP" : ai >= 60 ? "B SETUP" : "WATCH");
        row.put("omsEligible", false);
        row.put("signalAgeSec", 0);
        row.put("regimeFit", Math.abs(s.dayChangePct()) >= 0.5);
        return row;
    }

    private Map<String, Object> fromRankingBoard(CurrentSetup setup) {
        Map<String, Object> row = new LinkedHashMap<>();
        int ai = setup.getQualityScore() != null
                ? setup.getQualityScore().setScale(0, RoundingMode.HALF_UP).intValue()
                : 70;
        row.put("symbol", setup.getStockId());
        row.put("ltp", setup.getEntryPrice());
        row.put("side", "BUY");
        row.put("aiScore", ai);
        row.put("probability", ai);
        row.put("executionStatus", "WATCHLIST");
        row.put("pipelineStage", "QUALITY_PASSED");
        row.put("rejectionReason", null);
        row.put("setupType", setup.getSetupType());
        row.put("strategy", "SETUP_DETECT");
        row.put("status", "WATCHLIST");
        row.put("displayStatus", "WATCHING");
        row.put("source", "RANKING_BOARD");
        if (setup.getEntryPrice() != null) {
            row.put("entryPrice", setup.getEntryPrice());
        }
        if (setup.getStopLoss() != null) {
            row.put("stopLoss", setup.getStopLoss());
        }
        if (setup.getTargetPrice() != null) {
            row.put("targetPrice", setup.getTargetPrice());
        }
        row.put("reason", setup.getSetupType() != null
                ? setup.getSetupType().replace('_', ' ') + " structure on ranking board"
                : "Ranking board setup");
        row.put("tradeQuality", setup.getConfidenceLevel());
        row.put("omsEligible", false);
        row.put("qualityGate", "PASSED");
        row.put("riskGate", "N/A");
        row.put("lifecycle", List.of("DETECTED", "VALIDATED", "QUALITY_PASSED"));
        row.put("createdAt", setup.getTimeDetected() != null ? setup.getTimeDetected().toString() : null);
        return row;
    }

    private List<String> resolveUniverseSymbols() {
        Optional<StrategyUniverseGroup> group = groupRepository.findByGroupKey(universeGroupKey);
        if (group.isEmpty()) {
            group = groupRepository.findByGroupKey("NIFTY_100");
        }
        if (group.isEmpty()) {
            group = groupRepository.findByGroupKey("NIFTY_50");
        }
        if (group.isEmpty()) {
            return List.of();
        }
        return symbolRepository.findAllByGroupIdAndEnabledTrue(group.get().getId()).stream()
                .map(StrategyUniverseSymbol::getSymbol)
                .filter(s -> s != null && !s.isBlank())
                .limit(moverScanLimit)
                .toList();
    }

    private static LocalDate resolveSessionDay(List<MarketdataCandle> bars, LocalDate today) {
        boolean hasToday = bars.stream()
                .anyMatch(b -> b.getOpenTime() != null
                        && b.getOpenTime().atZone(IST).toLocalDate().equals(today));
        if (hasToday) {
            return today;
        }
        return bars.stream()
                .filter(b -> b.getOpenTime() != null)
                .map(b -> b.getOpenTime().atZone(IST).toLocalDate())
                .max(LocalDate::compareTo)
                .orElse(today);
    }

    private static boolean isIndexSymbol(String sym) {
        String u = sym.toUpperCase();
        return u.contains("NIFTY") || u.contains("SENSEX") || u.contains("BANKEX") || u.contains("FINNIFTY");
    }

    private static String stripNse(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw.trim();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String fmtPrice(Object ltp) {
        if (ltp instanceof BigDecimal bd) {
            return bd.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        if (ltp instanceof Number n) {
            return String.format("%.2f", n.doubleValue());
        }
        return ltp != null ? String.valueOf(ltp) : "—";
    }

    private record MoverScore(
            String symbol,
            BigDecimal ltp,
            double dayChangePct,
            double recentMomentumPct,
            long volume,
            double activityScore,
            double volumeFactor) {

        double absDayChange() {
            return Math.abs(dayChangePct);
        }
    }
}
