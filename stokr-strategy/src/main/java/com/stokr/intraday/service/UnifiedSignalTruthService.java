package com.stokr.intraday.service;

import com.stokr.intraday.engine.MarketRegimeDetector;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.catalog.StrategyUniverseResolverService;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.pipeline.SignalPipelineAudit;
import com.stokr.strategy.pipeline.SignalPipelineAuditService;
import com.stokr.strategy.pipeline.SignalPipelineEligibilityService;
import com.stokr.strategy.pipeline.SignalPipelineEligibilityService.EligibilityResult;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.runtime.StrategyRegistry;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import com.stokr.strategy.context.StrategyContext;
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
    private final StrategyRegistry strategyRegistry;
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

        for (StrategySignalEntity sig : persisted) {
            if (sig.getCreatedAt() != null && sig.getCreatedAt().isBefore(dayStart)) {
                continue;
            }
            if (Boolean.TRUE.equals(sig.getTestTrade())) {
                continue;
            }
            String key = dedupeKey(sig.getStrategyName(), sig.getSymbol());
            if (!seenKeys.add(key)) {
                continue;
            }
            EligibilityResult elig = eligibilityService.enrichPersistedSignal(sig);
            scannerRows.add(toUnifiedRow(sig, elig, scannerRows.size() + 1));
        }

        for (SignalPipelineAudit audit : auditService.recentToday(userId, 80)) {
            String key = dedupeKey(audit.getStrategyKey(), audit.getSymbol());
            if (!seenKeys.add(key)) {
                continue;
            }
            if ("EXECUTED".equals(audit.getExecutionStatus()) || "OMS_ELIGIBLE".equals(audit.getExecutionStatus())) {
                continue;
            }
            scannerRows.add(auditToRow(audit, scannerRows.size() + 1));
        }

        appendScanPreview(scannerRows, seenKeys, now, 40);

        scannerRows.sort(Comparator
                .comparingInt((Map<String, Object> r) -> statusRank(String.valueOf(r.get("executionStatus"))))
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
        out.put("decisions", buildDecisions(scannerRows));
        out.put("sectors", buildSectors(scannerRows));
        out.put("risk", Map.of("openRisk", "—", "capitalUsedPct", 0, "corrRisk", "LOW"));
        out.put("performance", buildPerformance(dayStart));
        out.put("liveControl", eligibilityService.liveControlPanel(now));
        out.put("scanIntervalSec", (int) Math.max(1, scanPollMs / 1000));
        out.put("truthSource", "PRODUCTION_PIPELINE");
        return out;
    }

    private void appendScanPreview(
            List<Map<String, Object>> rows,
            Set<String> seenKeys,
            Instant now,
            int maxPreview) {
        if (rows.size() >= maxPreview) {
            return;
        }
        List<StrategyRuntimeBinding> bindings = universeResolver.resolveActiveBindings();
        int added = 0;
        for (StrategyRuntimeBinding binding : bindings) {
            if (added >= maxPreview) {
                break;
            }
            String strategyKey = binding.getStrategyCatalog().getStrategyKey();
            TradingStrategy strategy = strategyRegistry.get(strategyKey);
            if (strategy == null) {
                continue;
            }
            List<StrategyUniverseSymbol> symbols =
                    universeResolver.resolveSymbolEntitiesForGroup(binding.getUniverseGroup().getId());
            int symLimit = Math.min(8, symbols.size());
            for (int i = 0; i < symLimit && added < maxPreview; i++) {
                StrategyUniverseSymbol sym = symbols.get(i);
                String symbol = sym.getTradingSymbol() != null ? sym.getTradingSymbol() : sym.getSymbol();
                String key = dedupeKey(strategyKey, symbol);
                if (!seenKeys.add(key)) {
                    continue;
                }
                StrategyContext ctx = new StrategyContext(symbol, now, Map.of(), BigDecimal.ZERO);
                StrategySignal evaluated;
                try {
                    evaluated = strategy.evaluate(ctx);
                } catch (Exception ex) {
                    continue;
                }
                if (evaluated == null || evaluated.type() == SignalType.HOLD) {
                    EligibilityResult gate = eligibilityService.evaluatePrePersist(strategyKey, symbol, null, now);
                    if (gate.rejectionMessage() != null && !"EXECUTABLE".equals(gate.executionStatus())) {
                        rows.add(previewRow(strategyKey, symbol, gate, rows.size() + 1, null));
                        added++;
                    }
                    continue;
                }
                StrategySignalEntity preview = new StrategySignalEntity();
                preview.setStrategyName(strategyKey);
                preview.setSymbol(symbol);
                preview.setSignalType(evaluated.type());
                preview.setEntryReferencePrice(evaluated.entryPrice());
                preview.setStopPrice(evaluated.stopPrice());
                preview.setTargetPrice(evaluated.targetPrice());
                preview.setReason(evaluated.reason());
                EligibilityResult elig = eligibilityService.evaluatePrePersist(strategyKey, symbol, preview, now);
                rows.add(previewRow(strategyKey, symbol, elig, rows.size() + 1, preview));
                added++;
            }
        }
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
        return row;
    }

    private Map<String, Object> previewRow(
            String strategyKey,
            String symbol,
            EligibilityResult elig,
            int rank,
            StrategySignalEntity preview) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("signalId", null);
        row.put("rank", rank);
        row.put("symbol", stripNse(symbol));
        row.put("strategy", strategyKey);
        row.put("side", preview != null && preview.getSignalType() != null ? preview.getSignalType().name() : "—");
        row.put("ltp", preview != null ? preview.getEntryReferencePrice() : lastPrice(symbol));
        row.put("aiScore", preview != null ? aiScore(preview.getConfidenceScore(), preview) : 0);
        row.put("probability", row.get("aiScore"));
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
        row.put("setupType", strategyKey);
        row.put("status", elig.executionStatus());
        row.put("signalAgeSec", 0);
        row.put("tradeQuality", tradeQualityLabel((Integer) row.get("aiScore")));
        row.put("omsEligible", "EXECUTABLE".equals(elig.executionStatus()));
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
            p.put("executionStatus", row.get("executionStatus"));
            p.put("rejectionReason", row.get("rejectionReason"));
            p.put("obi", "—");
            p.put("trend", row.get("executionStatus"));
            out.add(p);
        }
        return out;
    }

    private List<Map<String, Object>> buildDecisions(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows.stream().limit(20).toList()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("time", row.get("createdAt") != null
                    ? String.valueOf(row.get("createdAt")).substring(11, 16) : "—");
            d.put("symbol", row.get("symbol"));
            d.put("action", row.get("side"));
            d.put("strategy", row.get("strategy"));
            d.put("aiScore", row.get("aiScore"));
            d.put("executionStatus", row.get("executionStatus"));
            d.put("rejectionReason", row.get("rejectionReason"));
            d.put("lifecycle", row.get("lifecycle"));
            d.put("result", row.get("outcomeStatus") != null ? row.get("outcomeStatus") : row.get("executionStatus"));
            out.add(d);
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
            s.put("stocks", e.getValue().stream().map(r -> r.get("symbol")).limit(6).toList());
            sectors.add(s);
        }
        return sectors;
    }

    private Map<String, Object> buildPerformance(Instant dayStart) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("trades", signalRepository.countByCreatedAtAfterAndDeletedFalse(dayStart));
        p.put("winRate", "—");
        p.put("avgLatencyMs", "—");
        p.put("fillRate", "—");
        return p;
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
