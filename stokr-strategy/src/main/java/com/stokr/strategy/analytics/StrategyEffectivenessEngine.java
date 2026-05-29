package com.stokr.strategy.analytics;

import com.stokr.common.simulation.AnalyticsDataScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class StrategyEffectivenessEngine {

    public static final List<String> CANONICAL_STRATEGIES = List.of(
            "ADV_CASH",
            "GAP_FILL",
            "VWAP_BOUNCE",
            "NSE_SPIKE_DETECTION",
            "SECTOR_LAGGARD",
            "INDEX_HUNT",
            "EARLY_BREAKOUT",
            "S3_VWAP_RETEST",
            "S7_RANGE_FADE",
            "COMMODITIES_E2E_TEST"
    );

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final StrategyEffectivenessRepository repository;
    private final AlphaValidationEngine alphaValidationEngine;

    @Value("${stokr.analytics.v8-cutoff-instant:2026-05-29T17:35:00Z}")
    private Instant defaultV8Cutoff;

    public StrategyEffectivenessEngine(
            StrategyEffectivenessRepository repository,
            AlphaValidationEngine alphaValidationEngine
    ) {
        this.repository = repository;
        this.alphaValidationEngine = alphaValidationEngine;
    }

    public StrategyEffectivenessReport buildReport(LocalDate fromDate, LocalDate toDate, Instant v8CutoffOverride) {
        return buildReport(fromDate, toDate, v8CutoffOverride, AnalyticsDataScope.REAL);
    }

    public StrategyEffectivenessReport buildReport(
            LocalDate fromDate,
            LocalDate toDate,
            Instant v8CutoffOverride,
            AnalyticsDataScope scope
    ) {
        AnalyticsDataScope dataScope = scope != null ? scope : AnalyticsDataScope.REAL;
        LocalDate from = fromDate != null ? fromDate : LocalDate.now(IST).minusDays(30);
        LocalDate to = toDate != null ? toDate : LocalDate.now(IST);
        if (to.isBefore(from)) {
            to = from;
        }
        Instant fromInstant = from.atStartOfDay(IST).toInstant();
        Instant toExclusive = to.plusDays(1).atStartOfDay(IST).toInstant();
        Instant v8Cutoff = v8CutoffOverride != null ? v8CutoffOverride : defaultV8Cutoff;

        Map<String, Long> rejections = mapRejections(repository.rejectionsByStrategy(fromInstant, toExclusive));
        List<StrategyScorecard> scorecards = buildScorecards(
                repository.scorecardByStrategy(fromInstant, toExclusive, dataScope), rejections);
        Map<String, ProtectionImpact> protection = buildProtectionMap(
                repository.protectionImpactByStrategy(fromInstant, toExclusive));
        List<ConfidenceBucket> globalConfidence = buildConfidenceBuckets(
                repository.confidenceBuckets(fromInstant, toExclusive, null));
        Map<String, List<ConfidenceBucket>> confidenceByStrategy = new LinkedHashMap<>();
        for (String key : scorecards.stream().map(StrategyScorecard::strategyKey).toList()) {
            confidenceByStrategy.put(key, buildConfidenceBuckets(
                    repository.confidenceBuckets(fromInstant, toExclusive, key)));
        }
        List<StrategyLeaderboardEntry> leaderboard = buildLeaderboard(scorecards, protection);
        List<LiveReadiness> readiness = buildReadiness(scorecards, leaderboard);
        V8Comparison v8 = buildV8Comparison(repository.periodComparison(fromInstant, toExclusive, v8Cutoff));
        AlphaValidationEngine.AlphaValidationReport alphaValidation =
                dataScope == AnalyticsDataScope.REAL
                        ? alphaValidationEngine.buildReport(from, to, v8Cutoff)
                        : null;

        return new StrategyEffectivenessReport(
                from.toString(),
                to.toString(),
                v8Cutoff.toString(),
                dataScope.name(),
                scorecards,
                leaderboard,
                globalConfidence,
                confidenceByStrategy,
                protection,
                readiness,
                v8,
                alphaValidation
        );
    }

    public AlphaValidationEngine.AlphaValidationReport buildAlphaValidationReport(
            LocalDate fromDate,
            LocalDate toDate,
            Instant v8CutoffOverride
    ) {
        LocalDate from = fromDate != null ? fromDate : LocalDate.now(IST).minusDays(30);
        LocalDate to = toDate != null ? toDate : LocalDate.now(IST);
        Instant v8Cutoff = v8CutoffOverride != null ? v8CutoffOverride : defaultV8Cutoff;
        return alphaValidationEngine.buildReport(from, to, v8Cutoff);
    }

    private Map<String, Long> mapRejections(List<Object[]> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Object[] r : rows) {
            map.put(r[0].toString(), toLong(r[1]));
        }
        return map;
    }

    private List<StrategyScorecard> buildScorecards(List<Object[]> rows, Map<String, Long> rejections) {
        Map<String, StrategyScorecard> byKey = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String key = r[0].toString();
            long generated = toLong(r[1]);
            long executed = toLong(r[2]);
            long targetHits = toLong(r[3]);
            long slHits = toLong(r[4]);
            long protectionExits = toLong(r[5]);
            long feedProtection = toLong(r[6]);
            long expired = toLong(r[7]);
            long pending = toLong(r[8]);
            long running = toLong(r[9]);
            long closed = toLong(r[10]);
            long open = toLong(r[11]);
            double avgConfidence = toDouble(r[12]);
            double avgProbability = toDouble(r[13]);
            double avgRr = toDouble(r[14]);
            double avgHoldSec = toDouble(r[15]);
            double avgMfe = toDouble(r[16]);
            double avgMae = toDouble(r[17]);
            double maxMfe = toDouble(r[18]);
            double maxMae = toDouble(r[19]);
            long targetReach = toLong(r[20]);
            long stopReach = toLong(r[21]);
            long wins = toLong(r[22]);
            long losses = toLong(r[23]);
            double grossProfit = toDouble(r[24]);
            double grossLoss = toDouble(r[25]);
            double avgPnl = toDouble(r[26]);
            long confV2 = toLong(r[27]);
            long confNull = toLong(r[28]);

            long rejected = rejections.getOrDefault(key, 0L);
            long resolved = targetHits + slHits;
            double winRate = resolved == 0 ? 0 : (double) targetHits / resolved * 100.0;
            double lossRate = resolved == 0 ? 0 : (double) slHits / resolved * 100.0;
            double targetHitPct = generated == 0 ? 0 : (double) targetHits / generated * 100.0;
            double slHitPct = generated == 0 ? 0 : (double) slHits / generated * 100.0;
            double protectionPct = generated == 0 ? 0 : (double) protectionExits / generated * 100.0;
            double targetReachPct = generated == 0 ? 0 : (double) targetReach / generated * 100.0;
            double stopReachPct = generated == 0 ? 0 : (double) stopReach / generated * 100.0;
            double profitFactor = grossLoss == 0 ? (grossProfit > 0 ? 99.0 : 0) : grossProfit / grossLoss;
            double expectancy = (wins + losses) == 0 ? 0 : avgPnl;

            byKey.put(key, new StrategyScorecard(
                    key, generated, executed, targetHits, slHits, protectionExits, feedProtection,
                    expired, rejected, running, open, closed,
                    round(winRate), round(lossRate), round(targetHitPct), round(slHitPct), round(protectionPct),
                    round(avgHoldSec), round(avgConfidence * 100), round(avgProbability * 100), round(avgRr),
                    round(avgMfe), round(avgMae), round(maxMfe), round(maxMae),
                    round(targetReachPct), round(stopReachPct),
                    wins, losses, round(profitFactor), round(expectancy), confV2, confNull
            ));
        }

        for (String canonical : CANONICAL_STRATEGIES) {
            byKey.computeIfAbsent(canonical, k -> emptyScorecard(k, rejections.getOrDefault(k, 0L)));
        }

        return byKey.values().stream()
                .sorted(Comparator.comparingLong(StrategyScorecard::signalsGenerated).reversed())
                .toList();
    }

    private StrategyScorecard emptyScorecard(String key, long rejected) {
        return new StrategyScorecard(
                key, 0L, 0L, 0L, 0L, 0L, 0L, 0L, rejected, 0L, 0L, 0L,
                0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0,
                0L, 0L, 0.0, 0.0, 0L, 0L
        );
    }

    private Map<String, ProtectionImpact> buildProtectionMap(List<Object[]> rows) {
        Map<String, ProtectionImpact> map = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String key = r[0].toString();
            long protectedTrades = toLong(r[1]);
            long wouldTarget = toLong(r[2]);
            long wouldStop = toLong(r[3]);
            long missedProfit = wouldTarget;
            long savedLoss = wouldStop;
            double effectiveness = (missedProfit + savedLoss) == 0
                    ? 0
                    : (double) savedLoss / (missedProfit + savedLoss) * 100.0;
            map.put(key, new ProtectionImpact(
                    key, protectedTrades, wouldTarget, wouldStop, missedProfit, savedLoss, round(effectiveness)
            ));
        }
        return map;
    }

    private List<ConfidenceBucket> buildConfidenceBuckets(List<Object[]> rows) {
        List<ConfidenceBucket> out = new ArrayList<>();
        for (Object[] r : rows) {
            long signals = toLong(r[1]);
            long wins = toLong(r[2]);
            long losses = toLong(r[3]);
            long protection = toLong(r[4]);
            long targets = toLong(r[5]);
            long sl = toLong(r[6]);
            long resolved = targets + sl;
            double winRate = resolved == 0 ? 0 : (double) targets / resolved * 100.0;
            out.add(new ConfidenceBucket(
                    r[0].toString(), signals, wins, losses, protection, targets, sl, round(winRate)
            ));
        }
        return out;
    }

    private List<StrategyLeaderboardEntry> buildLeaderboard(
            List<StrategyScorecard> scorecards,
            Map<String, ProtectionImpact> protection
    ) {
        return scorecards.stream()
                .filter(s -> s.signalsGenerated() > 0)
                .map(s -> {
                    ProtectionImpact p = protection.get(s.strategyKey());
                    double confAccuracy = s.signalsGenerated() == 0 ? 0
                            : (double) s.confidenceV2Count() / s.signalsGenerated() * 100.0;
                    double riskAdj = s.expectancy() * (1.0 - s.protectionExitPct() / 100.0);
                    return new StrategyLeaderboardEntry(
                            s.strategyKey(),
                            s.expectancy(),
                            s.profitFactor(),
                            s.targetHitPct(),
                            round(riskAdj),
                            round(confAccuracy),
                            rankScore(s, p)
                    );
                })
                .sorted(Comparator.comparingDouble(StrategyLeaderboardEntry::rankScore).reversed())
                .toList();
    }

    private double rankScore(StrategyScorecard s, ProtectionImpact p) {
        double prot = p != null ? p.protectionEffectivenessPct() : 50.0;
        return s.expectancy() * 10 + s.profitFactor() * 5 + s.targetHitPct() * 0.5 + prot * 0.1;
    }

    private List<LiveReadiness> buildReadiness(
            List<StrategyScorecard> scorecards,
            List<StrategyLeaderboardEntry> leaderboard
    ) {
        Map<String, Integer> rankByStrategy = new LinkedHashMap<>();
        int i = 1;
        for (StrategyLeaderboardEntry e : leaderboard) {
            rankByStrategy.put(e.strategyKey(), i++);
        }
        List<LiveReadiness> out = new ArrayList<>();
        for (StrategyScorecard s : scorecards) {
            if (s.signalsGenerated() == 0 && s.rejected() == 0) {
                continue;
            }
            LiveReadinessTier tier = classify(s);
            String reason = explainReadiness(s, tier);
            out.add(new LiveReadiness(
                    s.strategyKey(),
                    tier.name(),
                    reason,
                    rankByStrategy.getOrDefault(s.strategyKey(), 0)
            ));
        }
        return out;
    }

    private LiveReadinessTier classify(StrategyScorecard s) {
        if (s.signalsGenerated() < 10) {
            return LiveReadinessTier.NOT_READY;
        }
        if (s.protectionExitPct() > 60 || s.winRate() < 35) {
            return LiveReadinessTier.NOT_READY;
        }
        if (s.signalsGenerated() < 30 || s.expectancy() <= 0) {
            return LiveReadinessTier.PAPER_ONLY;
        }
        if (s.signalsGenerated() < 50 || s.winRate() < 45 || s.profitFactor() < 1.0) {
            return LiveReadinessTier.LIMITED_LIVE;
        }
        if (s.winRate() >= 50 && s.expectancy() > 0 && s.profitFactor() >= 1.2 && s.protectionExitPct() < 40) {
            if (s.signalsGenerated() >= 100 && s.profitFactor() >= 1.5 && s.confidenceV2Count() > s.signalsGenerated() / 2) {
                return LiveReadinessTier.CAPITAL_ALLOCATION_READY;
            }
            return LiveReadinessTier.LIVE_READY;
        }
        return LiveReadinessTier.PAPER_ONLY;
    }

    private String explainReadiness(StrategyScorecard s, LiveReadinessTier tier) {
        return switch (tier) {
            case NOT_READY -> String.format(Locale.US,
                    "%d signals, win rate %.1f%%, protection exits %.1f%% — insufficient or poor outcomes.",
                    s.signalsGenerated(), s.winRate(), s.protectionExitPct());
            case PAPER_ONLY -> String.format(Locale.US,
                    "Expectancy %.2f, profit factor %.2f over %d signals — validate in PAPER before LIVE.",
                    s.expectancy(), s.profitFactor(), s.signalsGenerated());
            case LIMITED_LIVE -> String.format(Locale.US,
                    "Win rate %.1f%%, %d signals — marginal edge; cap size and monitor protection %.1f%%.",
                    s.winRate(), s.signalsGenerated(), s.protectionExitPct());
            case LIVE_READY -> String.format(Locale.US,
                    "Win rate %.1f%%, PF %.2f, expectancy %.2f, protection %.1f%% on %d signals.",
                    s.winRate(), s.profitFactor(), s.expectancy(), s.protectionExitPct(), s.signalsGenerated());
            case CAPITAL_ALLOCATION_READY -> String.format(Locale.US,
                    "Strong PF %.2f, win rate %.1f%%, %d signals, confidence V2 on %d — eligible for scaled capital.",
                    s.profitFactor(), s.winRate(), s.signalsGenerated(), s.confidenceV2Count());
        };
    }

    private V8Comparison buildV8Comparison(Object[] r) {
        return new V8Comparison(
                toLong(r[0]), toLong(r[1]),
                toLong(r[2]), toLong(r[3]),
                toLong(r[4]), toLong(r[5]),
                toLong(r[6]), toLong(r[7]),
                round(toDouble(r[8])), round(toDouble(r[9])),
                toLong(r[10]), toLong(r[11]), toLong(r[12]),
                toLong(r[13]), toLong(r[14])
        );
    }

    private static long toLong(Object o) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(o.toString());
    }

    private static double toDouble(Object o) {
        if (o == null) {
            return 0.0;
        }
        if (o instanceof BigDecimal bd) {
            return bd.doubleValue();
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(o.toString());
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public enum LiveReadinessTier {
        NOT_READY,
        PAPER_ONLY,
        LIMITED_LIVE,
        LIVE_READY,
        CAPITAL_ALLOCATION_READY
    }

    public record StrategyEffectivenessReport(
            String fromDate,
            String toDate,
            String v8CutoffInstant,
            String dataScope,
            List<StrategyScorecard> scorecards,
            List<StrategyLeaderboardEntry> leaderboard,
            List<ConfidenceBucket> globalConfidenceBuckets,
            Map<String, List<ConfidenceBucket>> confidenceByStrategy,
            Map<String, ProtectionImpact> protectionByStrategy,
            List<LiveReadiness> liveReadiness,
            V8Comparison v8Comparison,
            AlphaValidationEngine.AlphaValidationReport alphaValidation
    ) {
    }

    public record StrategyScorecard(
            String strategyKey,
            long signalsGenerated,
            long signalsExecuted,
            long targetHits,
            long stopLossHits,
            long protectionExits,
            long feedProtection,
            long expired,
            long rejected,
            long running,
            long open,
            long closed,
            double winRate,
            double lossRate,
            double targetHitPct,
            double slHitPct,
            double protectionExitPct,
            double avgHoldSeconds,
            double avgConfidencePct,
            double avgProbabilityPct,
            double avgRiskReward,
            double avgMfe,
            double avgMae,
            double maxMfe,
            double maxMae,
            double targetReachPct,
            double stopReachPct,
            long wins,
            long losses,
            double profitFactor,
            double expectancy,
            long confidenceV2Count,
            long confidenceNullCount
    ) {
    }

    public record ConfidenceBucket(
            String bucket,
            long signals,
            long wins,
            long losses,
            long protectionExits,
            long targetHits,
            long slHits,
            double winRate
    ) {
    }

    public record ProtectionImpact(
            String strategyKey,
            long protectedTrades,
            long wouldHaveHitTarget,
            long wouldHaveHitStop,
            long missedProfit,
            long savedLoss,
            double protectionEffectivenessPct
    ) {
    }

    public record StrategyLeaderboardEntry(
            String strategyKey,
            double expectancy,
            double profitFactor,
            double targetHitRate,
            double riskAdjustedReturn,
            double confidenceAccuracy,
            double rankScore
    ) {
    }

    public record LiveReadiness(
            String strategyKey,
            String tier,
            String reason,
            int leaderboardRank
    ) {
    }

    public record V8Comparison(
            long preSignalCount,
            long postSignalCount,
            long preTargetHits,
            long postTargetHits,
            long preSlHits,
            long postSlHits,
            long preProtectionExits,
            long postProtectionExits,
            double preAvgHoldSeconds,
            double postAvgHoldSeconds,
            long preConfidencePopulated,
            long postConfidencePopulated,
            long postConfidenceV2,
            long preMfeTracked,
            long postMfeTracked
    ) {
    }
}
