package com.stokr.smartstrategy;

import com.stokr.arbitrage.LivePosition;
import com.stokr.arbitrage.LivePositionRepository;
import com.stokr.arbitrage.AutoExecSettingRepository;
import com.stokr.arbitrage.AutoExecSetting;

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
    private final AdaptiveStrategyScanner adaptiveStrategyScanner;
    private final StrategyScoreEngine scoreEngine;
    private final PortfolioRiskManager riskManager;
    private final SmartStrategyExecutionService executionService;
    private final LivePositionRepository positionRepo;
    private final AutoExecSettingRepository autoExecSettingRepo;

    private final AtomicBoolean enabled;
    private final ConcurrentHashMap<String, Long> recentEntries;
    private static final long COOLDOWN_MS = 15 * 60 * 1000;
    private static final double MIN_SCORE = 65.0;
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
                                  AdaptiveStrategyScanner adaptiveStrategyScanner,
                                  StrategyScoreEngine scoreEngine,
                                  PortfolioRiskManager riskManager,
                                  SmartStrategyExecutionService executionService,
                                  LivePositionRepository positionRepo, AutoExecSettingRepository autoExecSettingRepo) {
        this.ratioButterflyScanner = ratioButterflyScanner;
        this.bwbScanner = bwbScanner;
        this.skewHarvestScanner = skewHarvestScanner;
        this.thetaCrushScanner = thetaCrushScanner;
        this.boxSpreadScanner = boxSpreadScanner;
        this.jadeLizardScanner = jadeLizardScanner;
        this.calendarSpreadScanner = calendarSpreadScanner;
        this.ironCondorScanner = ironCondorScanner;
        this.adaptiveStrategyScanner = adaptiveStrategyScanner;
        this.scoreEngine = scoreEngine;
        this.riskManager = riskManager;
        this.executionService = executionService;
        this.positionRepo = positionRepo;
        this.autoExecSettingRepo = autoExecSettingRepo;
        this.enabled = new AtomicBoolean(true);
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
            List<Map<String, Object>> adaptiveOpps = new ArrayList<>();
            safeScan("ADAPTIVE", () -> adaptiveStrategyScanner.scan("ALL"), adaptiveOpps);
            if (adaptiveOpps.size() > 3) {
                adaptiveOpps.sort((a, b) -> Double.compare(
                    ((Number) b.getOrDefault("compositeScore", b.getOrDefault("adaptiveScore", 0))).doubleValue(),
                    ((Number) a.getOrDefault("compositeScore", a.getOrDefault("adaptiveScore", 0))).doubleValue()
                ));
                adaptiveOpps = new ArrayList<>(adaptiveOpps.subList(0, 3));
            }
            allOpportunities.addAll(adaptiveOpps);
            safeScan("IRON_CONDOR", () -> ironCondorScanner.scan("ALL"), allOpportunities);
            safeScan("JADE_LIZARD", () -> jadeLizardScanner.scan("ALL"), allOpportunities);
            safeScan("BROKEN_WING_BUTTERFLY", () -> bwbScanner.scan("ALL"), allOpportunities);
            safeScan("RATIO_BUTTERFLY", () -> ratioButterflyScanner.scan("ALL"), allOpportunities);
            safeScan("SKEW_HARVEST", () -> skewHarvestScanner.scan("ALL"), allOpportunities);
            safeScan("BOX_SPREAD_ARB", () -> boxSpreadScanner.scan("ALL"), allOpportunities);
            safeScan("EXPIRY_THETA_CRUSH", () -> thetaCrushScanner.scan("ALL"), allOpportunities);
            safeScan("CALENDAR_SPREAD_EDGE", () -> calendarSpreadScanner.scan("ALL"), allOpportunities);

            // Filter out non-actionable opportunities (no legs = preview only)
            allOpportunities.removeIf(o -> o.get("legList") == null
                || "PRE_EXPIRY_SETUP".equals(o.get("subType")));

            // Score and rank — apply timing multiplier to effective threshold
            double effectiveMinScore = MIN_SCORE / timing.scoreMultiplier;
            List<Map<String, Object>> ranked = scoreEngine.rankAndFilter(allOpportunities, effectiveMinScore);

            // Apply timing multiplier to composite scores
            for (Map<String, Object> opp : ranked) {
                String stratTypeCheck = String.valueOf(opp.get("strategyType"));
                // Box/Parity permitted in paper with active bid/ask fills
                if (!isStrategyEnabled(stratTypeCheck)) {
                    log.info("SMART_AUTO: Skipped disabled strategy in config: {}", stratTypeCheck);
                    continue;
                }
                double configuredMinEdge = getMinEdgeForStrategy(stratTypeCheck);
                double actualOppEdge = getNetEdgeForOpp(opp);
                if (actualOppEdge > 0 && actualOppEdge < configuredMinEdge) {
                    log.info("SMART_AUTO: Skipped {} - Net Edge Rs.{} < Configured Min Net Edge Rs.{}", stratTypeCheck, String.format("%.0f", actualOppEdge), String.format("%.0f", configuredMinEdge));
                    continue;
                }
                double raw = ((Number) opp.get("compositeScore")).doubleValue();
                opp.put("compositeScore", Math.min(100, Math.round(raw * timing.scoreMultiplier * 100.0) / 100.0));
            }
            // Re-filter after multiplier application
            ranked.removeIf(opp -> ((Number) opp.get("compositeScore")).doubleValue() < MIN_SCORE);

            if (ranked.isEmpty()) {
                lastScanResult = "No opportunities above score " + MIN_SCORE + " (timing: " + timing.phase + ") — " + now();
                return;
            }

            log.info("SMART_AUTO: {} opportunities scored above {} (timing: {}, mult: {}), top score: {}",
                ranked.size(), MIN_SCORE, timing.phase, timing.scoreMultiplier, ranked.get(0).get("compositeScore"));

            // Try to enter the best one that passes risk checks
            List<LivePosition> openPositions = positionRepo.findAllOpen();
            int entered = 0;
            for (Map<String, Object> opp : ranked) {
                String stratTypeCheck = String.valueOf(opp.get("strategyType"));
                // Box/Parity permitted in paper with active bid/ask fills
                if (!isStrategyEnabled(stratTypeCheck)) {
                    log.info("SMART_AUTO: Skipped disabled strategy in config: {}", stratTypeCheck);
                    continue;
                }
                double configuredMinEdge = getMinEdgeForStrategy(stratTypeCheck);
                double actualOppEdge = getNetEdgeForOpp(opp);
                if (actualOppEdge > 0 && actualOppEdge < configuredMinEdge) {
                    log.info("SMART_AUTO: Skipped {} - Net Edge Rs.{} < Configured Min Net Edge Rs.{}", stratTypeCheck, String.format("%.0f", actualOppEdge), String.format("%.0f", configuredMinEdge));
                    continue;
                }
                String stratType = String.valueOf(opp.get("strategyType"));
                String und = String.valueOf(opp.get("underlying"));
                String expStr = String.valueOf(opp.getOrDefault("expiry", opp.getOrDefault("expiryDate", "")));

                // STRICT DEDUPLICATION: Skip if ANY position of the same strategy is already OPEN on this underlying
                boolean alreadyOpenOnUnderlying = openPositions.stream().anyMatch(pos ->
                    "OPEN".equals(pos.getStatus()) &&
                    stratType.equalsIgnoreCase(pos.getStrategyType()) &&
                    und.equalsIgnoreCase(pos.getUnderlying())
                );
                if (alreadyOpenOnUnderlying) {
                    log.info("SMART_AUTO: Skipped {} {} - already has an active OPEN position on this underlying", stratType, und);
                    continue;
                }

                // 15-Minute DB Cooldown Guard: Skip if same strategy and underlying entered within last 15 mins
                java.time.LocalDateTime dbCooldownCutoff = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).minusMinutes(15);
                boolean recentInDb = positionRepo.findAll().stream()
                    .filter(p -> p.getEnteredAt() != null && p.getEnteredAt().isAfter(dbCooldownCutoff))
                    .anyMatch(p -> stratType.equalsIgnoreCase(p.getStrategyType()) && und.equalsIgnoreCase(p.getUnderlying()));
                if (recentInDb) {
                    log.info("SMART_AUTO: Skipped {} {} - position entered within last 15 minutes", stratType, und);
                    continue;
                }

                // 15-Minute Re-entry Cooldown Guard: Skip if same strategy and underlying exited within last 15 mins
                boolean recentlyExited = positionRepo.findAll().stream()
                    .filter(p -> ("CLOSED".equals(p.getStatus()) || "EXITED".equals(p.getStatus()))
                            && p.getExitedAt() != null && p.getExitedAt().isAfter(dbCooldownCutoff))
                    .anyMatch(p -> stratType.equalsIgnoreCase(p.getStrategyType()) && und.equalsIgnoreCase(p.getUnderlying()));
                if (recentlyExited) {
                    log.info("SMART_AUTO: Skipped {} {} - position exited within last 15 minutes (re-entry cooldown)", stratType, und);
                    continue;
                }

                if (entered >= 1) break; // Max 1 entry per cycle

                String stratLimit = strategyLimitBreach(stratType, openPositions);
                if (stratLimit != null) {
                    log.info("SMART_AUTO: Skipped {} {} - {}", stratType, und, stratLimit);
                    continue;
                }

                // In-memory cooldown key at Strategy:Underlying level (not per strike)
                String key = stratType.toUpperCase() + ":" + und.toUpperCase();

                // Cooldown check
                Long lastEntry = recentEntries.get(key);
                if (lastEntry != null && System.currentTimeMillis() - lastEntry < COOLDOWN_MS) {
                    log.info("SMART_AUTO: Skipped {} {} - in-memory cooldown active", stratType, und);
                    continue;
                }

                // Portfolio risk check
                PortfolioRiskManager.RiskCheck riskCheck = riskManager.canEnterTrade(opp, lotsFor(opp));
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
        String strategyType = String.valueOf(opp.get("strategyType"));
        req.put("strategyType", strategyType);
        req.put("underlying", opp.get("underlying"));
        req.put("expiry", opp.get("expiry"));
        req.put("broker", "PAPER");
        req.put("legList", opp.get("legList"));
        req.put("action", opp.get("action"));

        req.put("maxLoss", PortfolioRiskManager.maxLossPerLot(opp));
        req.put("maxProfit", maxProfitPerLot(opp));
        req.put("lots", lotsFor(opp));

        // Strategy-specific exit rules. Time exits apply only to the expiry-day trade: multi-day
        // structures (condors, butterflies, calendars, skew) are built around decay into expiry, and
        // forcing them flat at 15:25 every day pays four legs of spread for a few hours of noise.
        // Positions still open on expiry are settled by the end-of-day expiry job.
        TimingWindow timing = getTimingWindow();
        switch (strategyType) {
            case "BOX_SPREAD_ARB" -> {
                // Payoff is locked at expiry — no SL/target (an early exit only pays the spread twice)
                req.put("holdType", "INTRADAY");
                req.put("slPct", 0.0);
                req.put("targetPct", 0.0);
                req.put("timeExitMinutes", 0);
            }
            case "IRON_CONDOR" -> {
                // Multi-day defined risk swing — capture multi-day and weekend theta decay
                req.put("holdType", "OVERNIGHT_SWING");
                req.put("slPct", "THETA_BOOST".equals(timing.phase) ? 35.0 : 45.0);
                req.put("targetPct", "THETA_BOOST".equals(timing.phase) ? 40.0 : 45.0);
                req.put("timeExitMinutes", 0);
            }
            case "ADAPTIVE", "JADE_LIZARD" -> {
                req.put("holdType", "INTRADAY");
                req.put("slPct", 40.0);
                req.put("targetPct", 45.0);
                req.put("timeExitMinutes", 75); // 14:15 IST exit
            }
            case "EXPIRY_THETA_CRUSH" -> {
                // Expiry-day trade — book fast, and be flat 3 minutes before close
                req.put("holdType", "INTRADAY");
                req.put("slPct", 30.0);
                req.put("targetPct", 40.0);
                req.put("timeExitMinutes", 3);
            }
            case "RATIO_BUTTERFLY" -> {
                // Asymmetric risk — strictly intraday with tight SL and 14:15 PM exit to avoid afternoon trend blowouts
                req.put("holdType", "INTRADAY");
                req.put("slPct", 35.0);
                req.put("targetPct", 40.0);
                req.put("timeExitMinutes", 75); // 14:15 IST exit
            }
            case "BROKEN_WING_BUTTERFLY" -> {
                // Broken wing — intraday with defined risk and 14:15 PM exit
                req.put("holdType", "INTRADAY");
                req.put("slPct", 40.0);
                req.put("targetPct", 40.0);
                req.put("timeExitMinutes", 75); // 14:15 IST exit
            }
            default -> {
                // SKEW_HARVEST, CALENDAR_SPREAD_EDGE
                req.put("holdType", "INTRADAY");
                req.put("slPct", 45.0);
                req.put("targetPct", 45.0);
                req.put("timeExitMinutes", 75);
            }
        }

        return req;
    }

    /** Score-based lot sizing: higher score = more conviction. */
    static int lotsForScore(Map<String, Object> opp) {
        return 1; // Strict 1-Lot Cap for all auto entries
    }

    /** Per-lot profit used for the target %, preferring the structure's true maximum. */
    static double maxProfitPerLot(Map<String, Object> opp) {
        for (String key : List.of("maxProfit", "netCreditRs", "creditRs", "edgeAfterCosts", "netEdgeRs")) {
            if (opp.get(key) instanceof Number n && n.doubleValue() > 0) return n.doubleValue();
        }
        return 0;
    }

    private boolean isMarketHours() {
        if (!com.stokr.marketdata.MarketCalendar.isTradingDayToday()) return false;
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        return !now.isBefore(LocalTime.of(9, 25)) && now.isBefore(LocalTime.of(15, 15));
    }

    private String now() {
        return ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
            .format(DateTimeFormatter.ofPattern("hh:mm:ss a"));
    }

    
        private double getMinEdgeForStrategy(String stratId) {
        if (stratId == null) return 500.0;
        String norm = com.stokr.arbitrage.OptionArbAutoExecService.normalizeStrategyId(stratId);

        AutoExecSetting s = autoExecSettingRepo.findBySettingKey("STRAT_" + norm + "_minEdge").orElse(null);
        if (s != null && s.getSettingValue() != null) {
            try { return Double.parseDouble(s.getSettingValue()); } catch (Exception ignored) {}
        }
        if (norm.contains("BID_PARITY")) return 1500.0;
        return norm.startsWith("CASH") ? 3.5 : 500.0;
    }

    private double getNetEdgeForOpp(Map<String, Object> opp) {
        for (String k : List.of("edgeAfterCosts", "netEdgeRs", "maxProfit", "netCreditRs", "creditRs")) {
            if (opp.containsKey(k) && opp.get(k) instanceof Number n) {
                return n.doubleValue();
            }
        }
        return 0.0;
    }

    private boolean isStrategyEnabled(String stratId) {
        if (stratId == null) return true;
        String val = settingRaw(stratId, "paperEnabled");
        return val == null || "true".equalsIgnoreCase(val.trim());
    }

    private String settingRaw(String stratId, String field) {
        String norm = com.stokr.arbitrage.OptionArbAutoExecService.normalizeStrategyId(stratId);
        AutoExecSetting s = autoExecSettingRepo.findBySettingKey("STRAT_" + norm + "_" + field).orElse(null);
        return s != null ? s.getSettingValue() : null;
    }

    private Double settingNumber(String stratId, String field) {
        String val = settingRaw(stratId, field);
        if (val == null) return null;
        try { return Double.parseDouble(val.trim()); } catch (Exception e) { return null; }
    }

    /** Strategy Control Center lots if saved, else the score-based default. */
    private int lotsFor(Map<String, Object> opp) {
        Double v = settingNumber(String.valueOf(opp.get("strategyType")), "lots");
        return v != null && v >= 1 ? v.intValue() : lotsForScore(opp);
    }

    /** Strategy Control Center maxActiveTrades / maxDailyLoss for paper positions; null if OK. */
    private String strategyLimitBreach(String stratId, List<LivePosition> openPositions) {
        Double maxActive = settingNumber(stratId, "maxActiveTrades");
        Double maxLoss = settingNumber(stratId, "maxDailyLoss");
        if (maxActive == null && maxLoss == null) return null;
        String norm = com.stokr.arbitrage.OptionArbAutoExecService.normalizeStrategyId(stratId);
        java.util.function.Predicate<LivePosition> mine = p -> "PAPER".equals(p.getBroker())
                && norm.equals(com.stokr.arbitrage.OptionArbAutoExecService.normalizeStrategyId(p.getStrategyType()));
        if (maxActive != null && openPositions.stream().filter(mine).count() >= maxActive)
            return "max active trades (" + maxActive.intValue() + ") reached";
        if (maxLoss != null && maxLoss > 0) {
            java.time.LocalDateTime dayStart = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).atStartOfDay();
            double pnl = positionRepo.findAll().stream().filter(mine)
                    .filter(p -> "OPEN".equals(p.getStatus()) || (p.getExitedAt() != null && !p.getExitedAt().isBefore(dayStart)))
                    .mapToDouble(p -> p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0.0).sum();
            if (pnl <= -maxLoss) return "daily loss limit Rs." + String.format("%.0f", maxLoss) + " hit";
        }
        return null;
    }

}