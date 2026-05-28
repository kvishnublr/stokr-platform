package com.stokr.intraday.service;

import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.engine.MarketRegimeDetector;
import com.stokr.intraday.stream.RealTimeSetupStream;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdvIntelligenceTerminalService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final RealTimeSetupStream realTimeStream;
    private final AdvIntelligenceFeedService feedService;
    private final MarketDataQueryService marketDataQueryService;
    private final StrategySignalRepository signalRepository;

    public Map<String, Object> buildTerminal(UUID userId) {
        if (realTimeStream.getStatistics().tickCount == 0) {
            feedService.refreshNow();
        }

        MarketRegimeDetector.MarketRegime regime = realTimeStream.getCurrentRegime();
        RealTimeSetupStream.StreamStatistics stats = realTimeStream.getStatistics();
        List<CurrentSetup> setups = realTimeStream.getRankingBoard();
        List<Map<String, Object>> scannerRows = buildScannerRows(setups);
        List<Map<String, Object>> liveCards = buildLiveCards(setups, scannerRows);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("marketRegime", regime.name());
        out.put("regimeNarrative", regimeNarrative(regime));
        out.put("marketOpen", isNseSessionOpen());
        out.put("istTime", LocalTime.now(IST).format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        out.put("metrics", buildMetrics(stats, scannerRows));
        out.put("scannerRows", scannerRows);
        out.put("liveCards", liveCards);
        out.put("engine", buildEngineStats(scannerRows));
        out.put("orderFlow", buildOrderFlow(scannerRows));
        out.put("decisions", buildDecisions(userId, setups));
        out.put("sectors", buildSectors(scannerRows));
        out.put("risk", buildRisk(userId));
        out.put("performance", buildPerformance(userId));
        out.put("correlation", buildCorrelation(scannerRows));
        out.put("strategyRegime", buildStrategyRegime());
        return out;
    }

    private Map<String, Object> buildMetrics(RealTimeSetupStream.StreamStatistics stats, List<Map<String, Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        int tracked = Math.max(stats.tickCount, rows.size());
        int active = Math.max(stats.rankingBoardSize, rows.size());
        if (tracked == 0 && !rows.isEmpty()) {
            tracked = rows.size();
            active = (int) rows.stream().filter(r -> {
                String st = String.valueOf(r.getOrDefault("status", ""));
                return "TRADING".equals(st) || "SIGNAL".equals(st) || "WATCHING".equals(st);
            }).count();
        }
        m.put("stocksTracked", tracked);
        m.put("activeSetups", active);
        m.put("topScore", stats.topSetupScore != null ? stats.topSetupScore.intValue() : topScore(rows));
        m.put("avgWinRate", estimateWinRate(rows));
        m.put("marketBreadth", estimateBreadth(rows));
        m.put("systemAccuracy", estimateWinRate(rows));
        m.put("netInstFlow", "—");
        return m;
    }

    private List<Map<String, Object>> buildScannerRows(List<CurrentSetup> setups) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        for (CurrentSetup setup : setups) {
            rows.add(scannerRowFromSetup(setup, rank++));
        }
        if (rows.size() < 8) {
            for (StrategySignalEntity sig : signalRepository.findTop30ByDeletedFalseAndTestTradeFalseOrderByCreatedAtDesc(PageRequest.of(0, 12))) {
                if (rows.size() >= 23) break;
                if (rows.stream().anyMatch(r -> setupSymbol(r).equalsIgnoreCase(sig.getSymbol()))) continue;
                rows.add(scannerRowFromSignal(sig, rows.size() + 1));
            }
        }
        if (rows.isEmpty()) {
            rows.addAll(fallbackMoverRows());
        }
        return rows;
    }

    private List<Map<String, Object>> fallbackMoverRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String sym : List.of("RELIANCE", "TCS", "ITC", "SBIN", "HDFCBANK", "TATAMOTORS")) {
            Map<String, Object> row = candleRow(sym);
            if (row != null) {
                row.put("rank", rows.size() + 1);
                rows.add(row);
            }
        }
        return rows;
    }

    private Map<String, Object> candleRow(String symbol) {
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc("NSE:" + symbol, "1m", 30);
        if (bars.isEmpty()) return null;
        MarketdataCandle last = bars.get(bars.size() - 1);
        BigDecimal close = last.getClosePrice();
        BigDecimal open = bars.get(0).getOpenPrice();
        if (close == null || open == null || open.signum() == 0) return null;
        BigDecimal pct = close.subtract(open).divide(open, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        int ai = Math.min(85, Math.max(42, 55 + pct.abs().intValue() * 8));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("ltp", close.setScale(2, RoundingMode.HALF_UP));
        row.put("changePct", pct.setScale(2, RoundingMode.HALF_UP));
        row.put("aiScore", ai);
        row.put("status", pct.signum() >= 0 ? "WATCHING" : "WATCHING");
        row.put("side", pct.signum() >= 0 ? "LONG" : "SHORT");
        row.put("setupType", "Momentum");
        row.put("buyPct", pct.signum() >= 0 ? 58 : 42);
        row.put("volumeMultiple", "1.2×");
        row.put("winPct", 62);
        row.put("regimeFit", true);
        row.put("badges", List.of("DATA FEED"));
        return row;
    }

    private Map<String, Object> scannerRowFromSetup(CurrentSetup setup, int rank) {
        int ai = setup.getQualityScore() != null ? setup.getQualityScore().intValue() : 60;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rank", rank);
        row.put("symbol", setup.getStockId());
        row.put("ltp", setup.getEntryPrice());
        row.put("changePct", BigDecimal.ZERO);
        row.put("aiScore", ai);
        row.put("status", ai >= 75 ? "TRADING" : "WATCHING");
        row.put("side", "LONG");
        row.put("setupType", formatSetup(setup.getSetupType()));
        row.put("buyPct", 55 + Math.min(20, ai / 5));
        row.put("volumeMultiple", "2.0×");
        row.put("winPct", setup.getAdjustedProbability() != null
                ? setup.getAdjustedProbability().multiply(BigDecimal.valueOf(100)).intValue() : 65);
        row.put("regimeFit", true);
        row.put("badges", List.of(formatSetup(setup.getSetupType()).toUpperCase()));
        row.put("entryPrice", setup.getEntryPrice());
        row.put("targetPrice", setup.getTargetPrice());
        row.put("stopLoss", setup.getStopLoss());
        return row;
    }

    private Map<String, Object> scannerRowFromSignal(StrategySignalEntity sig, int rank) {
        int ai = toAiScore(sig.getConfidenceScore(), 62);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rank", rank);
        row.put("symbol", stripNse(sig.getSymbol()));
        row.put("ltp", sig.getEntryReferencePrice());
        row.put("changePct", BigDecimal.ZERO);
        row.put("aiScore", ai);
        row.put("status", "SIGNAL");
        row.put("side", sig.getSignalType() != null ? sig.getSignalType().name() : "BUY");
        row.put("setupType", sig.getStrategyName());
        row.put("buyPct", 55);
        row.put("volumeMultiple", "—");
        row.put("winPct", ai);
        row.put("regimeFit", true);
        row.put("badges", List.of("LIVE SIGNAL"));
        return row;
    }

    private List<Map<String, Object>> buildLiveCards(List<CurrentSetup> setups, List<Map<String, Object>> scannerRows) {
        List<Map<String, Object>> cards = new ArrayList<>();
        List<Map<String, Object>> source = scannerRows.stream()
                .filter(r -> {
                    Object st = r.get("status");
                    return "TRADING".equals(st) || "SIGNAL".equals(st);
                })
                .limit(3)
                .toList();
        if (source.isEmpty()) {
            source = scannerRows.stream().limit(3).toList();
        }
        for (Map<String, Object> row : source) {
            Map<String, Object> card = new LinkedHashMap<>(row);
            card.put("pnl", "+₹0");
            card.put("pnlPct", "+0.00%");
            card.put("holdMinutes", 0);
            card.put("targetProgressPct", Math.min(90, (int) row.getOrDefault("aiScore", 50)));
            cards.add(card);
        }
        return cards;
    }

    private Map<String, Object> buildEngineStats(List<Map<String, Object>> scannerRows) {
        Instant start = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        long signalsToday = signalRepository.countByCreatedAtAfterAndDeletedFalse(start);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("trades", signalsToday);
        e.put("active", realTimeStream.getRankingBoard().size());
        e.put("winRate", estimateWinRate(scannerRows));
        e.put("realizedPnl", "+₹0");
        e.put("unrealizedPnl", "+₹0");
        e.put("profitFactor", "—");
        e.put("capitalUsedPct", 0);
        e.put("autoTradeOn", false);
        return e;
    }

    private List<Map<String, Object>> buildOrderFlow(List<Map<String, Object>> scannerRows) {
        List<Map<String, Object>> pressure = new ArrayList<>();
        for (Map<String, Object> row : scannerRows.stream().limit(8).toList()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("symbol", row.get("symbol"));
            int buyPct = (int) row.getOrDefault("buyPct", 50);
            p.put("buyPct", buyPct);
            p.put("sellPct", 100 - buyPct);
            p.put("obi", BigDecimal.valueOf((buyPct - 50) / 50.0).setScale(2, RoundingMode.HALF_UP));
            p.put("trend", buyPct >= 60 ? "Build" : buyPct <= 40 ? "Heavy sell" : "Stable");
            pressure.add(p);
        }
        return pressure;
    }

    private List<Map<String, Object>> buildDecisions(UUID userId, List<CurrentSetup> setups) {
        List<Map<String, Object>> out = new ArrayList<>();
        Instant since = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        List<StrategySignalEntity> signals = userId != null
                ? signalRepository.findRecentForTrader(userId, PageRequest.of(0, 15))
                : signalRepository.findTop30ByDeletedFalseAndTestTradeFalseOrderByCreatedAtDesc(PageRequest.of(0, 15));
        for (StrategySignalEntity sig : signals) {
            if (sig.getCreatedAt() != null && sig.getCreatedAt().isBefore(since)) continue;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("time", sig.getCreatedAt() != null
                    ? sig.getCreatedAt().atZone(IST).format(DateTimeFormatter.ofPattern("HH:mm")) : "—");
            d.put("symbol", stripNse(sig.getSymbol()));
            d.put("action", sig.getSignalType() != null ? sig.getSignalType().name() : "BUY");
            d.put("strategy", sig.getStrategyName());
            d.put("aiScore", toAiScore(sig.getConfidenceScore(), 60));
            d.put("factors", List.of(sig.getReason() != null ? sig.getReason() : "Signal emitted"));
            d.put("result", sig.getOutcomeStatus() != null ? sig.getOutcomeStatus() : "PENDING");
            out.add(d);
            if (out.size() >= 12) break;
        }
        return out;
    }

    private List<Map<String, Object>> buildSectors(List<Map<String, Object>> scannerRows) {
        Map<String, List<Map<String, Object>>> bySector = new LinkedHashMap<>();
        for (Map<String, Object> row : scannerRows) {
            String sector = sectorFor((String) row.get("symbol"));
            bySector.computeIfAbsent(sector, k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> sectors = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : bySector.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", e.getKey());
            s.put("stocks", e.getValue().stream().map(r -> r.get("symbol")).limit(5).toList());
            s.put("count", e.getValue().size());
            sectors.add(s);
        }
        return sectors;
    }

    private Map<String, Object> buildRisk(UUID userId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("openRisk", "—");
        r.put("capitalUsedPct", 0);
        r.put("corrRisk", "LOW");
        r.put("positions", List.of());
        return r;
    }

    private Map<String, Object> buildPerformance(UUID userId) {
        Instant since = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        long today = signalRepository.countByCreatedAtAfterAndDeletedFalse(since);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("trades", today);
        p.put("winRate", "—");
        p.put("grossPnl", "+₹0");
        p.put("avgLatencyMs", 45);
        p.put("fillRate", "—");
        return p;
    }

    private List<List<Object>> buildCorrelation(List<Map<String, Object>> rows) {
        List<String> syms = rows.stream().map(r -> (String) r.get("symbol")).limit(4).toList();
        List<List<Object>> matrix = new ArrayList<>();
        for (String a : syms) {
            List<Object> line = new ArrayList<>();
            line.add(a);
            for (String b : syms) {
                line.add(a.equals(b) ? "1.00" : "0.45");
            }
            matrix.add(line);
        }
        return matrix;
    }

    private List<Map<String, Object>> buildStrategyRegime() {
        return List.of(
                Map.of("name", "Gap Fill", "winPct", 82),
                Map.of("name", "VWAP Bounce", "winPct", 71),
                Map.of("name", "Breakout", "winPct", 58),
                Map.of("name", "Sector Lag", "winPct", 42)
        );
    }

    private static String regimeNarrative(MarketRegimeDetector.MarketRegime regime) {
        return switch (regime) {
            case TRENDING_UP -> "Trend day — breakouts and relative strength prioritized";
            case TRENDING_DOWN -> "Down-trend — defensive setups, fade rallies cautiously";
            case CHOPPY -> "Range/chop — mean reversion favored, breakouts down-ranked";
            case VOLATILE -> "High volatility — confidence scores compressed";
            case QUIET -> "Low participation — fewer actionable setups";
        };
    }

    private static boolean isNseSessionOpen() {
        LocalTime now = LocalTime.now(IST);
        return !now.isBefore(LocalTime.of(9, 15)) && !now.isAfter(LocalTime.of(15, 30));
    }

    private static String formatSetup(String type) {
        if (type == null) return "Setup";
        return type.replace('_', ' ');
    }

    private static String stripNse(String symbol) {
        if (symbol == null) return "";
        return symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;
    }

    private static String setupSymbol(Map<String, Object> row) {
        return String.valueOf(row.get("symbol"));
    }

    private static int topScore(List<Map<String, Object>> rows) {
        return rows.stream().mapToInt(r -> (int) r.getOrDefault("aiScore", 0)).max().orElse(0);
    }

    private List<Map<String, Object>> scannerRowsFromSetups(List<CurrentSetup> setups) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int i = 1;
        for (CurrentSetup s : setups) {
            rows.add(scannerRowFromSetup(s, i++));
        }
        return rows;
    }

    private static String estimateWinRate(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "—";
        double avg = rows.stream().mapToInt(r -> (int) r.getOrDefault("winPct", 60)).average().orElse(60);
        return String.format("%.1f%%", avg);
    }

    private static String estimateBreadth(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "—";
        long up = rows.stream().filter(r -> {
            Object c = r.get("changePct");
            if (c instanceof BigDecimal bd) return bd.signum() >= 0;
            return true;
        }).count();
        long down = Math.max(0, rows.size() - up);
        return up + ":" + down;
    }

    private static String sectorFor(String symbol) {
        return switch (symbol != null ? symbol.toUpperCase() : "") {
            case "SBIN", "HDFCBANK", "ICICIBANK", "AXISBANK", "KOTAKBANK" -> "Banking";
            case "TCS", "INFY", "WIPRO", "HCLTECH" -> "IT";
            case "TATAMOTORS", "MARUTI", "M&M" -> "Auto";
            case "TATASTEEL", "JSWSTEEL", "HINDALCO" -> "Metals";
            default -> "General";
        };
    }

    /** Normalizes stored confidence (0–1 or 0–100) to AI score 0–100. */
    private static int toAiScore(BigDecimal confidence, int fallback) {
        if (confidence == null) {
            return fallback;
        }
        if (confidence.compareTo(BigDecimal.ONE) <= 0) {
            return confidence.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
        }
        return confidence.setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
