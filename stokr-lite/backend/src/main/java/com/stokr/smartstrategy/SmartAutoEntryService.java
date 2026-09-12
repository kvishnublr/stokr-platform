package com.stokr.smartstrategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class SmartAutoEntryService {

    private final RatioButterflyScanner ratioButterflyScanner;
    private final BrokenWingButterflyScanner bwbScanner;
    private final SkewHarvestScanner skewHarvestScanner;
    private final ExpiryThetaCrushScanner thetaCrushScanner;
    private final BoxSpreadArbScanner boxSpreadScanner;
    private final JadeLizardScanner jadeLizardScanner;
    private final CalendarSpreadEdgeScanner calendarSpreadScanner;
    private final IronCondorScanner ironCondorScanner;
    private final StrategyScoreEngine scoreEngine;
    private final PortfolioRiskManager riskManager;
    private final SmartStrategyExecutionService executionService;

    private final AtomicBoolean enabled;
    private final ConcurrentHashMap<String, Long> recentEntries;
    private static final long COOLDOWN_MS = 5 * 60 * 1000;
    private static final double MIN_SCORE = 55.0;
    private volatile String lastScanResult = "Not started";
    private volatile String timingNote = "";

    public SmartAutoEntryService(RatioButterflyScanner ratioButterflyScanner,
                                  BrokenWingButterflyScanner bwbScanner,
                                  SkewHarvestScanner skewHarvestScanner,
                                  ExpiryThetaCrushScanner thetaCrushScanner,
                                  BoxSpreadArbScanner boxSpreadScanner,
                                  JadeLizardScanner jadeLizardScanner,
                                  CalendarSpreadEdgeScanner calendarSpreadScanner,
                                  IronCondorScanner ironCondorScanner,
                                  StrategyScoreEngine scoreEngine,
                                  PortfolioRiskManager riskManager,
                                  SmartStrategyExecutionService executionService) {
        this.ratioButterflyScanner = ratioButterflyScanner;
        this.bwbScanner = bwbScanner;
        this.skewHarvestScanner = skewHarvestScanner;
        this.thetaCrushScanner = thetaCrushScanner;
        this.boxSpreadScanner = boxSpreadScanner;
        this.jadeLizardScanner = jadeLizardScanner;
        this.calendarSpreadScanner = calendarSpreadScanner;
        this.ironCondorScanner = ironCondorScanner;
        this.scoreEngine = scoreEngine;
        this.riskManager = riskManager;
        this.executionService = executionService;
        this.enabled = new AtomicBoolean(false);
        this.recentEntries = new ConcurrentHashMap<>();
    }

    private TimingWindow getTimingWindow() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        // Opening volatility: avoid 9:15-9:30
        if (now.isBefore(LocalTime.of(9, 30))) {
            return new TimingWindow("OPENING_AVOID", 0, "Waiting for opening volatility to settle");
        }
        // Prime entry: 9:30-10:30 — trend established, good OI buildup
        if (now.isBefore(LocalTime.of(10, 30))) {
            return new TimingWindow("PRIME", 1.1, "Prime entry window — trend established");
        }
        // Mid-day: 10:30-13:30 — normal
        if (now.isBefore(LocalTime.of(13, 30))) {
            return new TimingWindow("MIDDAY", 1.0, "Mid-day — normal conditions");
        }
        // Theta boost: 13:30-14:45 — theta decay accelerates, great for sellers
        if (now.isBefore(LocalTime.of(14, 45))) {
            return new TimingWindow("THETA_BOOST", 1.15, "Theta boost zone — accelerated decay");
        }
        // Late entry avoid: 14:45-15:15 — gamma risk, avoid new positions
        if (now.isBefore(LocalTime.of(15, 15))) {
            return new TimingWindow("LATE_AVOID", 0, "Too close to market close — avoid new entries");
        }
        return new TimingWindow("CLOSED", 0, "Market closed");
    }

    record TimingWindow(String phase, double scoreMultiplier, String note) {}

    public void setEnabled(boolean on) {
        enabled.set(on);
        log.info("SMART_AUTO_ENTRY: {}", on ? "ENABLED" : "DISABLED");
    }

    public boolean isEnabled() { return enabled.get(); }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled.get());
        status.put("minScore", MIN_SCORE);
        status.put("cooldownMinutes", COOLDOWN_MS / 60000);
        status.put("lastScanResult", lastScanResult);
        status.put("recentEntries", recentEntries.size());
        TimingWindow timing = getTimingWindow();
        status.put("timingPhase", timing.phase);
        status.put("timingNote", timing.note);
        status.put("timingActive", timing.scoreMultiplier > 0);
        status.putAll(riskManager.getPortfolioSummary());
        return status;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void autoScan() {
        if (!enabled.get()) return;
        if (!isMarketHours()) return;

        // Intraday Timing Engine — skip entry during risky windows
        TimingWindow timing = getTimingWindow();
        timingNote = timing.note;
        if (timing.scoreMultiplier <= 0) {
            lastScanResult = "Timing block: " + timing.note + " — " + now();
            return;
        }

        try {
            List<Map<String, Object>> allOpportunities = new ArrayList<>();

            // Scan all 8 strategy types
            safeScan("IRON_CONDOR", () -> ironCondorScanner.scan("ALL"), allOpportunities);
            safeScan("JADE_LIZARD", () -> jadeLizardScanner.scan("ALL"), allOpportunities);
            safeScan("BROKEN_WING_BUTTERFLY", () -> bwbScanner.scan("ALL"), allOpportunities);
            safeScan("RATIO_BUTTERFLY", () -> ratioButterflyScanner.scan("ALL"), allOpportunities);
            safeScan("SKEW_HARVEST", () -> skewHarvestScanner.scan("ALL"), allOpportunities);
            safeScan("BOX_SPREAD_ARB", () -> boxSpreadScanner.scan("ALL"), allOpportunities);
            safeScan("EXPIRY_THETA_CRUSH", () -> thetaCrushScanner.scan("ALL"), allOpportunities);
            safeScan("CALENDAR_SPREAD_EDGE", () -> calendarSpreadScanner.scan("ALL"), allOpportunities);

            // Score and rank all
            List<Map<String, Object>> ranked = scoreEngine.rankAndFilter(allOpportunities, MIN_SCORE);

            if (ranked.isEmpty()) {
                lastScanResult = "No opportunities above score " + MIN_SCORE + " — " + now();
                return;
            }

            log.info("SMART_AUTO: {} opportunities scored above {}, top score: {}",
                ranked.size(), MIN_SCORE, ranked.get(0).get("compositeScore"));

            // Try to enter the best one that passes risk checks
            int entered = 0;
            for (Map<String, Object> opp : ranked) {
                if (entered >= 1) break; // Max 1 entry per cycle

                String key = opp.get("strategyType") + ":" + opp.get("underlying") + ":"
                    + opp.getOrDefault("putSellStrike", opp.getOrDefault("strike", ""));

                // Cooldown check
                Long lastEntry = recentEntries.get(key);
                if (lastEntry != null && System.currentTimeMillis() - lastEntry < COOLDOWN_MS) {
                    continue;
                }

                // Portfolio risk check
                PortfolioRiskManager.RiskCheck riskCheck = riskManager.canEnterTrade(opp);
                if (!riskCheck.allowed()) {
                    log.info("SMART_AUTO: Blocked by risk: {} — {}", key, riskCheck.reason());
                    continue;
                }

                // Execute as PAPER trade
                Map<String, Object> request = buildEntryRequest(opp);
                Map<String, Object> result = executionService.enterTrade(request);

                if ("SUCCESS".equals(result.get("status"))) {
                    recentEntries.put(key, System.currentTimeMillis());
                    entered++;
                    log.info("SMART_AUTO_ENTRY: {} score={} — {}",
                        key, opp.get("compositeScore"), result.get("message"));
                } else {
                    log.warn("SMART_AUTO_ENTRY failed: {} — {}", key, result.get("message"));
                }
            }

            lastScanResult = String.format("Scanned %d opps, %d above %.0f, entered %d — %s",
                allOpportunities.size(), ranked.size(), MIN_SCORE, entered, now());

            // Cleanup old cooldown entries
            long cutoff = System.currentTimeMillis() - COOLDOWN_MS * 2;
            recentEntries.entrySet().removeIf(e -> e.getValue() < cutoff);

        } catch (Exception e) {
            log.error("SMART_AUTO scan error: {}", e.getMessage());
            lastScanResult = "Error: " + e.getMessage() + " — " + now();
        }
    }

    private void safeScan(String type, java.util.function.Supplier<List<Map<String, Object>>> scanner,
                          List<Map<String, Object>> results) {
        try {
            List<Map<String, Object>> opps = scanner.get();
            if (opps != null) results.addAll(opps);
        } catch (Exception e) {
            log.debug("SMART_AUTO: {} scan failed: {}", type, e.getMessage());
        }
    }

    private Map<String, Object> buildEntryRequest(Map<String, Object> opp) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("strategyType", opp.get("strategyType"));
        req.put("underlying", opp.get("underlying"));
        req.put("expiry", opp.get("expiry"));
        req.put("lots", 1);
        req.put("broker", "PAPER");
        req.put("legList", opp.get("legList"));
        req.put("action", opp.get("action"));

        // Auto-set exit rules based on strategy characteristics
        double maxLoss = 0;
        if (opp.get("maxLoss") instanceof Number n) maxLoss = n.doubleValue();
        else if (opp.get("maxLossDown") instanceof Number n) maxLoss = n.doubleValue();

        double maxProfit = 0;
        if (opp.get("creditRs") instanceof Number n) maxProfit = n.doubleValue();
        else if (opp.get("edgeAfterCosts") instanceof Number n) maxProfit = n.doubleValue();

        req.put("maxLoss", maxLoss);
        req.put("maxProfit", maxProfit);

        // Timing-adaptive exit rules
        TimingWindow timing = getTimingWindow();
        if ("THETA_BOOST".equals(timing.phase)) {
            req.put("slPct", 40.0);
            req.put("targetPct", 50.0);
            req.put("timeExitMinutes", 5);
        } else {
            req.put("slPct", 50.0);
            req.put("targetPct", 60.0);
            req.put("timeExitMinutes", 5);
        }

        return req;
    }

    private boolean isMarketHours() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        return !now.isBefore(LocalTime.of(9, 20)) && now.isBefore(LocalTime.of(15, 15));
    }

    private String now() {
        return ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
            .format(DateTimeFormatter.ofPattern("hh:mm:ss a"));
    }
}
