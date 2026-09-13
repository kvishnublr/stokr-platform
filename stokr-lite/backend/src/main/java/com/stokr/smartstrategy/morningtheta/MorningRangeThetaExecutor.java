package com.stokr.smartstrategy.morningtheta;

import com.stokr.arbitrage.LivePosition;
import com.stokr.arbitrage.LivePositionRepository;
import com.stokr.arbitrage.OptionChainService;
import com.stokr.arbitrage.ZerodhaSpotPriceFetcher;
import com.stokr.arbitrage.FuturesKeyResolver;
import com.stokr.smartstrategy.SmartStrategyExecutionService;
import com.stokr.smartstrategy.StrategyScoreEngine;
import com.stokr.smartstrategy.morningtheta.MorningRangeTracker.RangeData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class MorningRangeThetaExecutor {

    private final MorningRangeThetaScanner scanner;
    private final MorningRangeTracker rangeTracker;
    private final SmartStrategyExecutionService executionService;
    private final StrategyScoreEngine scoreEngine;
    private final LivePositionRepository positionRepo;
    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private volatile String lastStatus = "Not started";
    private volatile LocalDate lastEntryDate;
    private final ConcurrentHashMap<String, Boolean> todayAdjusted = new ConcurrentHashMap<>();
    private volatile double todayPnl = 0;

    private static final double DAILY_LOSS_LIMIT = -8000.0;
    private static final Map<String, String> SPOT_KEYS = Map.of(
        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK"
    );

    public MorningRangeThetaExecutor(MorningRangeThetaScanner scanner,
                                      MorningRangeTracker rangeTracker,
                                      SmartStrategyExecutionService executionService,
                                      StrategyScoreEngine scoreEngine,
                                      LivePositionRepository positionRepo,
                                      OptionChainService optionChainService,
                                      ZerodhaSpotPriceFetcher spotFetcher) {
        this.scanner = scanner;
        this.rangeTracker = rangeTracker;
        this.executionService = executionService;
        this.scoreEngine = scoreEngine;
        this.positionRepo = positionRepo;
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
    }

    public void setEnabled(boolean on) {
        enabled.set(on);
        log.info("MORNING_THETA: Auto-execution {}", on ? "ENABLED" : "DISABLED");
    }

    public boolean isEnabled() { return enabled.get(); }

    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void execute() {
        if (!enabled.get()) return;

        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        // Reset daily state
        if (lastEntryDate == null || !lastEntryDate.equals(today)) {
            todayAdjusted.clear();
            todayPnl = 0;
        }

        // Only active 10:15 - 15:00
        if (now.isBefore(LocalTime.of(10, 15)) || now.isAfter(LocalTime.of(15, 0))) {
            return;
        }

        // Compute today's realized P&L from closed morning theta positions
        todayPnl = positionRepo.findAll().stream()
            .filter(p -> "MORNING_RANGE_THETA".equals(p.getStrategyType()))
            .filter(p -> ("CLOSED".equals(p.getStatus()) || "EXITED".equals(p.getStatus())))
            .filter(p -> p.getExitedAt() != null && p.getExitedAt().toLocalDate().equals(today))
            .mapToDouble(p -> p.getCurrentPnl() != null ? p.getCurrentPnl().doubleValue() : 0)
            .sum();

        // Daily loss limit check
        if (todayPnl < DAILY_LOSS_LIMIT) {
            lastStatus = String.format("Daily loss limit hit: ₹%.0f (limit ₹%.0f) — stopped", todayPnl, DAILY_LOSS_LIMIT);
            return;
        }

        List<LivePosition> openPositions = getOpenMorningThetaPositions();

        // Monitor existing positions for adjustments
        for (LivePosition pos : openPositions) {
            monitorAndAdjust(pos);
        }

        // Entry: only if no open morning theta position for this underlying today
        if (now.isBefore(LocalTime.of(14, 30))) {
            tryEntry(today, openPositions);
        }
    }

    private void tryEntry(LocalDate today, List<LivePosition> openPositions) {
        // Max 1 position per underlying
        Set<String> openUnderlyings = new HashSet<>();
        for (LivePosition p : openPositions) {
            openUnderlyings.add(p.getUnderlying());
        }

        // Also check closed positions today (max 1 trade per underlying per day)
        Set<String> tradedToday = new HashSet<>(openUnderlyings);
        positionRepo.findAll().stream()
            .filter(p -> "MORNING_RANGE_THETA".equals(p.getStrategyType()))
            .filter(p -> p.getEnteredAt() != null && p.getEnteredAt().toLocalDate().equals(today))
            .forEach(p -> tradedToday.add(p.getUnderlying()));

        List<Map<String, Object>> opps = scanner.scan("ALL");
        if (opps.isEmpty()) {
            lastStatus = "No opportunities found — " + nowStr();
            return;
        }

        // Score and filter
        List<Map<String, Object>> scored = scoreEngine.rankAndFilter(opps, 50.0);
        if (scored.isEmpty()) {
            lastStatus = "No opportunities above score threshold — " + nowStr();
            return;
        }

        int entered = 0;
        for (Map<String, Object> opp : scored) {
            String underlying = (String) opp.get("underlying");
            if (tradedToday.contains(underlying)) continue;
            if (entered >= 2) break;

            Map<String, Object> request = buildEntryRequest(opp);
            Map<String, Object> result = executionService.enterTrade(request);

            if ("SUCCESS".equals(result.get("status"))) {
                entered++;
                lastEntryDate = today;
                tradedToday.add(underlying);
                double score = opp.get("compositeScore") instanceof Number n ? n.doubleValue() : 0;
                log.info("MORNING_THETA_ENTRY: {} {} dayType={} score={} credit=₹{}",
                    underlying, opp.get("subType"), opp.get("dayType"), Math.round(score), opp.get("netCreditRs"));
                lastStatus = String.format("Entered %s %s (score %.0f) — %s",
                    underlying, opp.get("dayType"), score, nowStr());
            }
        }
    }

    private void monitorAndAdjust(LivePosition pos) {
        String underlying = pos.getUnderlying();
        String posKey = pos.getId() + ":" + underlying;

        // Get current spot
        String spotKey = SPOT_KEYS.getOrDefault(underlying, "NSE:NIFTY 50");
        double[] spotFut = spotFetcher.getSpotAndFutures(spotKey,
            FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey));
        double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
        if (spot <= 0) return;

        RangeData range = rangeTracker.getRange(underlying);
        if (range == null) return;

        List<Map<String, Object>> legs = pos.getLegs();
        if (legs == null || legs.isEmpty()) return;

        // Find sold strikes
        int ceSellStrike = 0, peSellStrike = 0;
        for (Map<String, Object> leg : legs) {
            if ("SELL".equals(leg.get("side"))) {
                int strike = ((Number) leg.get("strike")).intValue();
                if ("CE".equals(leg.get("optionType"))) ceSellStrike = strike;
                else peSellStrike = strike;
            }
        }

        // Check for adjustment trigger: spot moves 60% toward a sold strike from midpoint
        boolean alreadyAdjusted = todayAdjusted.getOrDefault(posKey, false);
        if (!alreadyAdjusted) {
            if (ceSellStrike > 0 && peSellStrike > 0) {
                double midpoint = (ceSellStrike + peSellStrike) / 2.0;
                double ceDistance = ceSellStrike - spot;
                double peDistance = spot - peSellStrike;
                double ceFullDistance = ceSellStrike - midpoint;
                double peFullDistance = midpoint - peSellStrike;

                // CE side threatened — spot has covered 60%+ of the distance from midpoint to sold CE
                if (ceFullDistance > 0 && ceDistance > 0 && ceDistance < ceFullDistance * 0.4) {
                    log.info("MORNING_THETA: {} CE side threatened — spot {} approaching sold {}CE ({}% of distance)",
                        underlying, Math.round(spot), ceSellStrike,
                        Math.round((1 - ceDistance / ceFullDistance) * 100));
                    todayAdjusted.put(posKey, true);
                }
                // PE side threatened — spot has covered 60%+ of the distance from midpoint to sold PE
                if (peFullDistance > 0 && peDistance > 0 && peDistance < peFullDistance * 0.4) {
                    log.info("MORNING_THETA: {} PE side threatened — spot {} approaching sold {}PE ({}% of distance)",
                        underlying, Math.round(spot), peSellStrike,
                        Math.round((1 - peDistance / peFullDistance) * 100));
                    todayAdjusted.put(posKey, true);
                }
            }
        }

        // Breach check: if spot has crossed a sold strike, the exit system handles it via SL%
        // No additional action needed here — OptionArbAutoExecService.checkRollover() handles exits
    }

    private Map<String, Object> buildEntryRequest(Map<String, Object> opp) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("strategyType", "MORNING_RANGE_THETA");
        req.put("underlying", opp.get("underlying"));
        req.put("expiry", opp.get("expiry"));
        req.put("lots", 1);
        req.put("broker", "PAPER");
        req.put("legList", opp.get("legList"));
        req.put("action", opp.get("action"));

        double maxLoss = opp.get("maxLoss") instanceof Number n ? n.doubleValue() : 0;
        double maxProfit = opp.get("maxProfit") instanceof Number n ? n.doubleValue() : 0;
        req.put("maxLoss", maxLoss);
        req.put("maxProfit", maxProfit);

        // Intraday exits: SL at 100% of premium, target at 50%, exit 45 min before close
        req.put("slPct", 100.0);
        req.put("targetPct", 50.0);
        req.put("timeExitMinutes", 45);

        return req;
    }

    private List<LivePosition> getOpenMorningThetaPositions() {
        return positionRepo.findAllOpen().stream()
            .filter(p -> "OPEN".equals(p.getStatus()) && "MORNING_RANGE_THETA".equals(p.getStrategyType()))
            .toList();
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled.get());
        status.put("lastStatus", lastStatus);
        status.put("todayPnl", todayPnl);
        status.put("dailyLossLimit", DAILY_LOSS_LIMIT);

        // Morning range data
        Map<String, Object> ranges = new LinkedHashMap<>();
        for (var entry : rangeTracker.getAllRanges().entrySet()) {
            RangeData r = entry.getValue();
            Map<String, Object> rd = new LinkedHashMap<>();
            rd.put("open", r.openPrice());
            rd.put("high", r.high());
            rd.put("low", r.low());
            rd.put("current", r.currentSpot());
            rd.put("rangePct", r.rangePercent());
            rd.put("dayType", r.dayType().name());
            rd.put("trend", r.trend().name());
            rd.put("atmIV", r.atmIV());
            rd.put("frozen", r.frozen());
            ranges.put(entry.getKey(), rd);
        }
        status.put("morningRanges", ranges);
        status.put("trackingPhase", rangeTracker.isTrackingPhase());

        // Open positions
        List<LivePosition> open = getOpenMorningThetaPositions();
        status.put("openPositions", open.size());
        status.put("positions", open.stream().map(LivePosition::toMap).toList());

        return status;
    }

    private String nowStr() {
        return java.time.ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
            .format(java.time.format.DateTimeFormatter.ofPattern("hh:mm:ss a"));
    }
}
