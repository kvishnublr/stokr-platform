package com.stokr.intraday.service;

import com.stokr.intraday.engine.MarketRegimeDetector;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.catalog.StrategyUniverseResolverService;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.pipeline.SignalPipelineAudit;
import com.stokr.strategy.pipeline.SignalPipelineAuditService;
import com.stokr.strategy.pipeline.SignalPipelineEligibilityService;
import com.stokr.strategy.pipeline.SignalPipelineEligibilityService.EligibilityResult;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * V8 unified signal truth — ADV terminal reflects production pipeline only.
 */
@Service
@RequiredArgsConstructor
public class UnifiedSignalTruthService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final StrategySignalRepository signalRepository;
    private final SignalPipelineEligibilityService eligibilityService;
    private final SignalPipelineAuditService auditService;
    private final StrategyUniverseResolverService universeResolver;
    private final LiveIntradayMoverService liveMoverService;
    private final MarketDataQueryService marketDataQueryService;
    private final MarketRegimeDetector regimeDetector;

    @Value("${stokr.catalog.scan.poll-ms:10000}")
    private long scanPollMs;

    public Map<String, Object> buildTerminal(UUID userId) {
        Instant now = Instant.now();
        Instant dayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();

        List<StrategySignalEntity> persisted = userId != null
                ? signalRepository.findRecentForTrader(userId, PageRequest.of(0, 100))
                : signalRepository.findTop30ByDeletedFalseAndTestTradeFalseOrderByCreatedAtDesc(PageRequest.of(0, 100));

        List<Map<String, Object>> scannerRows = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        Set<String> seenSymbols = new LinkedHashSet<>();

        appendLiveMarketRows(scannerRows, seenSymbols, 28);

        for (StrategySignalEntity sig : persisted) {
            if (sig.getCreatedAt() != null && sig.getCreatedAt().isBefore(dayStart)) {
                continue;
            }
            if (Boolean.TRUE.equals(sig.getTestTrade())) {
                continue;
            }
            String sym = stripNse(sig.getSymbol());
            if (!seenSymbols.add(sym)) {
                continue;
            }
            String key = dedupeKey(sig.getStrategyName(), sym);
            seenKeys.add(key);
            EligibilityResult elig = eligibilityService.enrichPersistedSignal(sig);
            scannerRows.add(toUnifiedRow(sig, elig, 0));
        }

        int auditAdded = 0;
        List<SignalPipelineAudit> audits = auditService.recentToday(userId, 40);
        for (SignalPipelineAudit audit : audits) {
            if (auditAdded >= 10) {
                break;
            }
            String sym = stripNse(audit.getSymbol());
            if (!seenSymbols.add(sym)) {
                continue;
            }
            if ("EXECUTED".equals(audit.getExecutionStatus()) || "OMS_ELIGIBLE".equals(audit.getExecutionStatus())) {
                continue;
            }
            String key = dedupeKey(audit.getStrategyKey(), sym);
            if (!seenKeys.add(key)) {
                continue;
            }
            scannerRows.add(auditToRow(audit, 0));
            auditAdded++;
        }

        for (Map<String, Object> row : scannerRows) {
            enrichDisplayFields(row);
        }

        scannerRows.sort(Comparator
                .comparingInt((Map<String, Object> r) -> sourceRank(String.valueOf(r.getOrDefault("source", ""))))
                .thenComparingInt((Map<String, Object> r) -> statusRank(String.valueOf(r.get("executionStatus"))))
                .thenComparing((Map<String, Object> r) -> (Double) r.getOrDefault("activityScore", 0.0), Comparator.reverseOrder())
                .thenComparing((Map<String, Object> r) -> (Integer) r.getOrDefault("aiScore", 0), Comparator.reverseOrder()));

        int rank = 1;
        for (Map<String, Object> row : scannerRows) {
            row.put("rank", rank++);
        }

        RegimeSnapshot regime = computeRegime();
        Map<String, Object> metrics = buildMetrics(scannerRows, regime);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("marketRegime", regime.regime().name());
        out.put("regimeNarrative", regime.narrative());
        out.put("marketOpen", isNseSessionOpen());
        out.put("istTime", LocalTime.now(IST).format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        out.put("metrics", metrics);
        out.put("scannerRows", scannerRows);
        out.put("liveCards", buildLiveCards(scannerRows));
        out.put("engine", buildEngine(scannerRows, dayStart));
        out.put("orderFlow", buildOrderFlow(scannerRows));
        out.put("orderFlowSummary", buildOrderFlowSummary(scannerRows));
        out.put("decisions", buildDecisions(persisted, audits, dayStart));
        out.put("rejectedSetups", buildRejectedSetups(scannerRows));
        out.put("sectors", buildSectors(scannerRows));
        out.put("risk", buildRisk(scannerRows));
        out.put("performance", buildPerformance(dayStart, scannerRows));
        out.put("systemHealth", buildSystemHealth(now));
        out.put("liveControl", eligibilityService.liveControlPanel(now));
        out.put("scanIntervalSec", (int) Math.max(1, scanPollMs / 1000));
        out.put("truthSource", "LIVE_SCANNER + PRODUCTION");
        return out;
    }

    private void appendLiveMarketRows(List<Map<String, Object>> rows, Set<String> seenSymbols, int maxRows) {
        for (Map<String, Object> live : liveMoverService.liveScannerRows(maxRows)) {
            String sym = String.valueOf(live.get("symbol"));
            if (!seenSymbols.add(sym)) {
                continue;
            }
            rows.add(live);
        }
    }

    private List<Map<String, Object>> buildDecisions(
            List<StrategySignalEntity> persisted,
            List<SignalPipelineAudit> audits,
            Instant dayStart) {
        List<Map<String, Object>> out = new ArrayList<>();

        for (StrategySignalEntity sig : persisted) {
            if (sig.getCreatedAt() != null && sig.getCreatedAt().isBefore(dayStart)) {
                continue;
            }
            if (Boolean.TRUE.equals(sig.getTestTrade())) {
                continue;
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("time", sig.getCreatedAt() != null
                    ? sig.getCreatedAt().atZone(IST).format(DateTimeFormatter.ofPattern("HH:mm")) : "—");
            d.put("symbol", stripNse(sig.getSymbol()));
            d.put("action", sig.getSignalType() != null ? sig.getSignalType().name() : "SIGNAL");
            d.put("strategy", sig.getStrategyName());
            d.put("aiScore", aiScore(sig.getConfidenceScore(), sig));
            d.put("executionStatus", sig.getOutcomeStatus() != null ? sig.getOutcomeStatus() : "PIPELINE");
            d.put("rejectionReason", null);
            d.put("decisionFactors", List.of("Persisted signal", sig.getReason() != null ? sig.getReason() : "—"));
            d.put("result", sig.getOutcomeStatus() != null ? sig.getOutcomeStatus() : "ACTIVE");
            out.add(d);
            if (out.size() >= 15) {
                break;
            }
        }

        for (SignalPipelineAudit audit : audits) {
            if (out.size() >= 20) {
                break;
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("time", audit.getCreatedAt() != null
                    ? audit.getCreatedAt().atZone(IST).format(DateTimeFormatter.ofPattern("HH:mm")) : "—");
            d.put("symbol", stripNse(audit.getSymbol()));
            d.put("action", "REJECTED");
            d.put("strategy", audit.getStrategyKey());
            d.put("aiScore", audit.getConfidenceScore() != null ? aiScore(audit.getConfidenceScore(), null) : 0);
            d.put("executionStatus", audit.getExecutionStatus());
            d.put("rejectionReason", audit.getRejectionMessage());
            d.put("decisionFactors", List.of(
                    audit.getRejectionCode() != null ? audit.getRejectionCode() : "REJECTED",
                    audit.getRejectionMessage() != null ? audit.getRejectionMessage() : "—"));
            d.put("result", audit.getExecutionStatus());
            out.add(d);
        }
        return out;
    }

    private Map<String, Object> toUnifiedRow(StrategySignalEntity sig, EligibilityResult elig, int rank) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("signalId", sig.getId() != null ? sig.getId().toString() : null);
        row.put("rank", rank);
        row.put("symbol", stripNse(sig.getSymbol()));
        row.put("strategy", sig.getStrategyName());
        row.put("side", sig.getSignalType() != null ? sig.getSignalType().name() : "BUY");
        row.put("ltp", sig.getEntryReferencePrice());
        row.put("aiScore", aiScore(sig.getConfidenceScore(), sig));
        row.put("probability", aiScore(sig.getConfidenceScore(), sig));
        row.put("executionStatus", elig.executionStatus());
        row.put("pipelineStage", elig.pipelineStage());
        row.put("rejectionReason", elig.rejectionMessage());
        row.put("rejectionCode", elig.rejectionCode());
        row.put("requestedMode", elig.requestedMode());
        row.put("effectiveMode", elig.effectiveMode());
        row.put("qualityGate", elig.qualityGate());
        row.put("riskGate", elig.riskGate());
        row.put("cooldownSecRemaining", elig.cooldownSecRemaining());
        row.put("lifecycle", elig.lifecycle());
        row.put("setupType", sig.getStrategyName());
        row.put("status", elig.executionStatus());
        row.put("signalAgeSec", sig.getCreatedAt() != null
                ? ChronoUnit.SECONDS.between(sig.getCreatedAt(), Instant.now()) : 0);
        row.put("outcomeStatus", sig.getOutcomeStatus());
        row.put("reason", sig.getReason());
        row.put("createdAt", sig.getCreatedAt() != null ? sig.getCreatedAt().toString() : null);
        row.put("tradeQuality", tradeQualityLabel((Integer) row.get("aiScore")));
        row.put("omsEligible", "EXECUTABLE".equals(elig.executionStatus()) || "EXECUTED".equals(elig.executionStatus()));
        row.put("source", "PRODUCTION");
        return row;
    }

    private Map<String, Object> auditToRow(SignalPipelineAudit audit, int rank) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("signalId", audit.getSignalId() != null ? audit.getSignalId().toString() : null);
        row.put("rank", rank);
        row.put("symbol", stripNse(audit.getSymbol()));
        row.put("strategy", audit.getStrategyKey());
        row.put("side", "—");
        row.put("ltp", null);
        row.put("aiScore", audit.getConfidenceScore() != null
                ? aiScore(audit.getConfidenceScore(), null) : 0);
        row.put("probability", row.get("aiScore"));
        row.put("executionStatus", audit.getExecutionStatus());
        row.put("pipelineStage", audit.getPipelineStage());
        row.put("rejectionReason", audit.getRejectionMessage());
        row.put("rejectionCode", audit.getRejectionCode());
        row.put("requestedMode", audit.getRequestedMode());
        row.put("effectiveMode", audit.getEffectiveMode());
        row.put("qualityGate", audit.getQualityGate());
        row.put("riskGate", audit.getRiskGate());
        row.put("cooldownSecRemaining", audit.getCooldownSecRemaining() != null ? audit.getCooldownSecRemaining() : 0);
        row.put("lifecycle", List.of(audit.getPipelineStage()));
        row.put("setupType", audit.getStrategyKey());
        row.put("status", audit.getExecutionStatus());
        row.put("signalAgeSec", audit.getCreatedAt() != null
                ? ChronoUnit.SECONDS.between(audit.getCreatedAt(), Instant.now()) : 0);
        row.put("tradeQuality", tradeQualityLabel((Integer) row.get("aiScore")));
        row.put("omsEligible", false);
        row.put("source", "PRODUCTION");
        return row;
    }

    private Map<String, Object> buildMetrics(List<Map<String, Object>> rows, RegimeSnapshot regime) {
        Map<String, Object> m = new LinkedHashMap<>();
        long executable = rows.stream().filter(r -> "EXECUTABLE".equals(r.get("executionStatus"))).count();
        long executed = rows.stream().filter(r -> "EXECUTED".equals(r.get("executionStatus"))).count();
        m.put("stocksTracked", universeSymbolCount());
        m.put("activeSetups", rows.size());
        m.put("executableCount", executable);
        m.put("executedCount", executed);
        m.put("topScore", rows.stream().mapToInt(r -> (int) r.getOrDefault("aiScore", 0)).max().orElse(0));
        m.put("marketBreadth", regime.breadth());
        m.put("scanIntervalSec", (int) Math.max(1, scanPollMs / 1000));
        m.put("avgWinRate", "—");
        m.put("systemAccuracy", "—");
        return m;
    }

    private List<Map<String, Object>> buildLiveCards(List<Map<String, Object>> rows) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String st = String.valueOf(row.get("executionStatus"));
            if (!"EXECUTABLE".equals(st) && !"EXECUTED".equals(st) && !"WATCHLIST".equals(st)) {
                continue;
            }
            cards.add(new LinkedHashMap<>(row));
            if (cards.size() >= 3) {
                break;
            }
        }
        if (cards.isEmpty()) {
            rows.stream()
                    .sorted(Comparator.comparingInt((Map<String, Object> r) ->
                            (Integer) r.getOrDefault("aiScore", 0)).reversed())
                    .limit(3)
                    .forEach(r -> cards.add(new LinkedHashMap<>(r)));
        }
        return cards;
    }

    private Map<String, Object> buildEngine(List<Map<String, Object>> rows, Instant dayStart) {
        long signalsToday = signalRepository.countByCreatedAtAfterAndDeletedFalse(dayStart);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("trades", signalsToday);
        e.put("active", rows.size());
        e.put("executable", rows.stream().filter(r -> "EXECUTABLE".equals(r.get("executionStatus"))).count());
        e.put("winRate", "—");
        return e;
    }

    private List<Map<String, Object>> buildOrderFlow(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows.stream().limit(12).toList()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("symbol", row.get("symbol"));
            p.put("buyPct", row.get("buyPct"));
            p.put("sellPct", row.get("sellPct"));
            p.put("executionStatus", row.get("executionStatus"));
            p.put("rejectionReason", row.get("rejectionReason"));
            p.put("obi", deriveObi(row));
            p.put("trend", deriveFlowTrend(row));
            out.add(p);
        }
        return out;
    }

    private Map<String, Object> buildOrderFlowSummary(List<Map<String, Object>> rows) {
        int bullish = 0;
        int bearish = 0;
        for (Map<String, Object> row : rows) {
            int buyPct = (Integer) row.getOrDefault("buyPct", 50);
            if (buyPct >= 55) {
                bullish++;
            } else if (buyPct <= 45) {
                bearish++;
            }
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("bullishCount", bullish);
        s.put("bearishCount", bearish);
        s.put("neutralCount", Math.max(0, rows.size() - bullish - bearish));
        s.put("imbalanceIndex", rows.isEmpty() ? 0
                : rows.stream().mapToInt(r -> (Integer) r.getOrDefault("buyPct", 50) - 50).average().orElse(0) / 50.0);
        return s;
    }

    private List<Map<String, Object>> buildRejectedSetups(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String st = String.valueOf(row.get("executionStatus"));
            if ("INTELLIGENCE_ONLY".equals(st) || "LIVE_MARKET".equals(row.get("source"))) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("symbol", row.get("symbol"));
            r.put("strategy", row.get("strategy"));
            r.put("aiScore", row.get("aiScore"));
            r.put("executionStatus", st);
            r.put("rejectionReason", row.get("rejectionReason"));
            r.put("rejectionCode", row.get("rejectionCode"));
            out.add(r);
            if (out.size() >= 12) {
                break;
            }
        }
        return out;
    }

    private List<Map<String, Object>> buildSectors(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> by = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String sector = sectorFor(String.valueOf(row.get("symbol")));
            by.computeIfAbsent(sector, k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> sectors = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : by.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", e.getKey());
            s.put("count", e.getValue().size());
            List<Map<String, Object>> stockDetails = new ArrayList<>();
            for (Map<String, Object> row : e.getValue().stream().limit(6).toList()) {
                Map<String, Object> sd = new LinkedHashMap<>();
                sd.put("symbol", row.get("symbol"));
                sd.put("aiScore", row.get("aiScore"));
                sd.put("executionStatus", row.get("executionStatus"));
                sd.put("side", row.get("side"));
                stockDetails.add(sd);
            }
            s.put("stocks", stockDetails);
            int adv = (int) e.getValue().stream().filter(r -> (Integer) r.getOrDefault("buyPct", 50) >= 50).count();
            s.put("advancers", adv);
            s.put("decliners", e.getValue().size() - adv);
            s.put("avgScore", e.getValue().stream().mapToInt(r -> (Integer) r.getOrDefault("aiScore", 0)).average().orElse(0));
            sectors.add(s);
        }
        sectors.sort(Comparator.comparingDouble((Map<String, Object> s) ->
                (Double) s.getOrDefault("avgScore", 0.0)).reversed());
        return sectors;
    }

    private Map<String, Object> buildRisk(List<Map<String, Object>> rows) {
        long blocked = rows.stream().filter(r -> {
            String st = String.valueOf(r.get("executionStatus"));
            return "BLOCKED".equals(st) || "REJECTED".equals(st) || "OMS_REJECTED".equals(st);
        }).count();
        long cooldown = rows.stream().filter(r -> "COOLDOWN".equals(r.get("executionStatus"))).count();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("openRisk", blocked > 0 ? blocked + " blocked signals" : "—");
        r.put("capitalUsedPct", rows.isEmpty() ? 0 : (int) Math.min(100, rows.stream()
                .filter(row -> "EXECUTABLE".equals(row.get("executionStatus"))
                        || "EXECUTED".equals(row.get("executionStatus"))).count() * 100 / Math.max(1, rows.size())));
        r.put("corrRisk", blocked >= 5 ? "HIGH" : blocked >= 2 ? "MEDIUM" : "LOW");
        r.put("blockedCount", blocked);
        r.put("cooldownCount", cooldown);
        r.put("positions", rows.stream()
                .filter(row -> "EXECUTABLE".equals(row.get("executionStatus"))
                        || "EXECUTED".equals(row.get("executionStatus")))
                .limit(8)
                .map(row -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("symbol", row.get("symbol"));
                    p.put("side", row.get("side"));
                    p.put("aiScore", row.get("aiScore"));
                    p.put("executionStatus", row.get("executionStatus"));
                    return p;
                })
                .toList());
        return r;
    }

    private Map<String, Object> buildPerformance(Instant dayStart, List<Map<String, Object>> rows) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("trades", signalRepository.countByCreatedAtAfterAndDeletedFalse(dayStart));
        p.put("winRate", "—");
        p.put("avgLatencyMs", "—");
        p.put("fillRate", "—");
        Map<String, List<Map<String, Object>>> bySetup = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String setup = String.valueOf(row.getOrDefault("setupType", row.get("strategy")));
            bySetup.computeIfAbsent(setup, k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> bySetupType = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : bySetup.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("setup", e.getKey());
            item.put("count", e.getValue().size());
            item.put("executable", e.getValue().stream()
                    .filter(r -> "EXECUTABLE".equals(r.get("executionStatus"))).count());
            item.put("avgScore", e.getValue().stream()
                    .mapToInt(r -> (Integer) r.getOrDefault("aiScore", 0)).average().orElse(0));
            bySetupType.add(item);
        }
        p.put("bySetupType", bySetupType);
        return p;
    }

    private Map<String, Object> buildSystemHealth(Instant now) {
        Map<String, Object> panel = eligibilityService.liveControlPanel(now);
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("feedOperational", panel.get("feedOperational"));
        h.put("safeStartupReady", panel.get("safeStartupReady"));
        h.put("liveEnabled", panel.get("liveEnabled"));
        h.put("marketOpen", panel.get("marketOpen"));
        h.put("scanIntervalSec", panel.get("scanIntervalSec"));
        h.put("status", Boolean.TRUE.equals(panel.get("feedOperational"))
                && Boolean.TRUE.equals(panel.get("safeStartupReady")) ? "OPERATIONAL" : "DEGRADED");
        return h;
    }

    private List<String> buildDecisionFactors(Map<String, Object> row) {
        List<String> factors = new ArrayList<>();
        factors.add("AI " + row.getOrDefault("aiScore", 0));
        if (row.get("qualityGate") != null) {
            factors.add("Q:" + row.get("qualityGate"));
        }
        if (row.get("rejectionReason") != null) {
            factors.add(String.valueOf(row.get("rejectionReason")));
        } else if (Boolean.TRUE.equals(row.get("omsEligible"))) {
            factors.add("OMS eligible");
        }
        return factors;
    }

    private void enrichDisplayFields(Map<String, Object> row) {
        if (!row.containsKey("activityScore")) {
            row.put("activityScore", 0.0);
        }
        if (!row.containsKey("buyPct")) {
            int score = (Integer) row.getOrDefault("aiScore", 0);
            String side = String.valueOf(row.getOrDefault("side", "BUY"));
            boolean isBuy = side.contains("BUY") || side.contains("LONG");
            int buyPct = isBuy
                    ? Math.min(95, Math.max(35, 40 + score / 2))
                    : Math.max(5, Math.min(45, 60 - score / 2));
            row.put("buyPct", buyPct);
            row.put("sellPct", 100 - buyPct);
        }
        if (!row.containsKey("displayStatus")) {
            row.put("displayStatus", mapDisplayStatus(String.valueOf(row.get("executionStatus"))));
        }
        if (!row.containsKey("regimeFit")) {
            row.put("regimeFit", "EXECUTABLE".equals(row.get("executionStatus"))
                    || "WATCHLIST".equals(row.get("executionStatus"))
                    || "EXECUTED".equals(row.get("executionStatus")));
        }
        if (!row.containsKey("volumeMultiple")) {
            row.put("volumeMultiple", "—");
        }
        if (!row.containsKey("winPct")) {
            row.put("winPct", "—");
        }
    }

    private static String mapDisplayStatus(String executionStatus) {
        return switch (executionStatus) {
            case "EXECUTABLE", "EXECUTED" -> "TRADING";
            case "WATCHLIST" -> "WATCHING";
            case "COOLDOWN" -> "COOLDOWN";
            default -> executionStatus.replace('_', ' ');
        };
    }

    private static String deriveObi(Map<String, Object> row) {
        if ("OBI_UNAVAILABLE".equals(row.get("rejectionCode"))) {
            return "UNAVAILABLE";
        }
        int buyPct = (Integer) row.getOrDefault("buyPct", 50);
        double obi = (buyPct - 50) / 50.0;
        return String.format("%+.2f", obi);
    }

    private static String deriveFlowTrend(Map<String, Object> row) {
        int buyPct = (Integer) row.getOrDefault("buyPct", 50);
        if (buyPct >= 60) {
            return "Build";
        }
        if (buyPct <= 40) {
            return "Heavy sell";
        }
        return "Neutral";
    }

    private record RegimeSnapshot(MarketRegimeDetector.MarketRegime regime, String narrative, String breadth) {}

    private RegimeSnapshot computeRegime() {
        int adv = 0;
        int dec = 0;
        BigDecimal niftyChange = BigDecimal.ZERO;
        for (String sym : List.of("RELIANCE", "TCS", "HDFCBANK", "INFY", "ITC", "SBIN")) {
            List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc("NSE:" + sym, "1m", 30);
            if (bars.size() < 2) {
                continue;
            }
            BigDecimal open = bars.get(0).getOpenPrice();
            BigDecimal close = bars.get(bars.size() - 1).getClosePrice();
            if (open == null || close == null || open.signum() == 0) {
                continue;
            }
            if (close.compareTo(open) >= 0) {
                adv++;
            } else {
                dec++;
            }
        }
        List<MarketdataCandle> nifty = marketDataQueryService.lastBarsAsc("NSE:NIFTY 50", "1m", 30);
        if (nifty.isEmpty()) {
            nifty = marketDataQueryService.lastBarsAsc("NSE:NIFTY50", "1m", 30);
        }
        if (nifty.size() >= 2) {
            BigDecimal o = nifty.get(0).getOpenPrice();
            BigDecimal c = nifty.get(nifty.size() - 1).getClosePrice();
            if (o != null && c != null && o.signum() > 0) {
                niftyChange = c.subtract(o).divide(o, 6, RoundingMode.HALF_UP);
            }
        }
        var snap = regimeDetector.detectRegime(
                BigDecimal.valueOf(22000),
                niftyChange,
                niftyChange.abs(),
                BigDecimal.valueOf(0.012),
                BigDecimal.valueOf(1.0));
        return new RegimeSnapshot(
                snap.regime,
                regimeNarrative(snap.regime),
                adv + ":" + dec);
    }

    private int universeSymbolCount() {
        int count = 0;
        for (StrategyRuntimeBinding b : universeResolver.resolveActiveBindings()) {
            count += universeResolver.resolveSymbolEntitiesForGroup(b.getUniverseGroup().getId()).size();
        }
        return Math.max(count, 0);
    }

    private BigDecimal lastPrice(String symbol) {
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(
                symbol.startsWith("NSE:") ? symbol : "NSE:" + symbol, "1m", 1);
        if (bars.isEmpty()) {
            return null;
        }
        return bars.get(bars.size() - 1).getClosePrice();
    }

    private static int aiScore(BigDecimal confidence, StrategySignalEntity sig) {
        if (confidence != null) {
            if (confidence.compareTo(BigDecimal.ONE) <= 0) {
                return confidence.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
            }
            return confidence.setScale(0, RoundingMode.HALF_UP).intValue();
        }
        if (sig != null && sig.getEntryReferencePrice() != null && sig.getStopPrice() != null && sig.getTargetPrice() != null) {
            BigDecimal risk = sig.getEntryReferencePrice().subtract(sig.getStopPrice()).abs();
            BigDecimal reward = sig.getTargetPrice().subtract(sig.getEntryReferencePrice()).abs();
            if (risk.signum() > 0) {
                double rr = reward.divide(risk, 4, RoundingMode.HALF_UP).doubleValue();
                return (int) Math.min(85, Math.max(45, 50 + rr * 15));
            }
        }
        return 0;
    }

    private static String tradeQualityLabel(int score) {
        if (score >= 85) return "A+ SETUP";
        if (score >= 75) return "A SETUP";
        if (score >= 65) return "B SETUP";
        if (score >= 50) return "WEAK SETUP";
        return "HIGH RISK";
    }

    private static int sourceRank(String source) {
        return switch (source) {
            case "RANKING_BOARD" -> 0;
            case "LIVE_MARKET" -> 1;
            case "PRODUCTION" -> 2;
            default -> 3;
        };
    }

    private static int statusRank(String status) {
        return switch (status) {
            case "EXECUTABLE" -> 0;
            case "EXECUTED" -> 1;
            case "WATCHLIST" -> 2;
            case "COOLDOWN" -> 3;
            case "BLOCKED" -> 4;
            case "QUALITY_REJECTED" -> 5;
            case "OMS_REJECTED" -> 6;
            default -> 7;
        };
    }

    private static String dedupeKey(String strategy, String symbol) {
        return (strategy != null ? strategy : "") + "|" + stripNse(symbol);
    }

    private static String stripNse(String symbol) {
        if (symbol == null) return "";
        return symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;
    }

    private static String sectorFor(String symbol) {
        return switch (symbol != null ? symbol.toUpperCase() : "") {
            case "SBIN", "HDFCBANK", "ICICIBANK" -> "Banking";
            case "TCS", "INFY", "WIPRO" -> "IT";
            case "TATAMOTORS", "MARUTI" -> "Auto";
            default -> "General";
        };
    }

    private static String regimeNarrative(MarketRegimeDetector.MarketRegime regime) {
        return switch (regime) {
            case TRENDING_UP -> "Trend day — production pipeline prioritizes relative strength";
            case TRENDING_DOWN -> "Down-trend — defensive setups only";
            case CHOPPY -> "Range/chop — mean reversion favored, breakouts down-ranked";
            case VOLATILE -> "High volatility — confidence compressed";
            case QUIET -> "Low participation — fewer executable setups";
        };
    }

    private static boolean isNseSessionOpen() {
        LocalTime now = LocalTime.now(IST);
        return !now.isBefore(LocalTime.of(9, 15)) && !now.isAfter(LocalTime.of(15, 30));
    }
}
