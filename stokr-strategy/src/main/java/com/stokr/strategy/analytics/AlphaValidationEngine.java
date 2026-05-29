package com.stokr.strategy.analytics;

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
import java.util.stream.Collectors;

/**
 * Production-only alpha validation: attribution, protection counterfactuals, alpha score, capital tiers.
 */
@Service
public class AlphaValidationEngine {

    public static final List<String> ALPHA_SPRINT_STRATEGIES = List.of(
            "ADV_CASH",
            "GAP_FILL",
            "VWAP_BOUNCE",
            "NSE_SPIKE_DETECTION",
            "SECTOR_LAGGARD",
            "INDEX_HUNT",
            "S3_VWAP_RETEST",
            "S7_RANGE_FADE"
    );

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final StrategyEffectivenessRepository repository;

    public AlphaValidationEngine(StrategyEffectivenessRepository repository) {
        this.repository = repository;
    }

    public AlphaValidationReport buildReport(LocalDate fromDate, LocalDate toDate, Instant v8Cutoff) {
        LocalDate from = fromDate != null ? fromDate : LocalDate.now(IST).minusDays(30);
        LocalDate to = toDate != null ? toDate : LocalDate.now(IST);
        if (to.isBefore(from)) {
            to = from;
        }
        Instant fromInstant = from.atStartOfDay(IST).toInstant();
        Instant toExclusive = to.plusDays(1).atStartOfDay(IST).toInstant();

        List<AlphaAttribution> attributions = buildAttributions(
                repository.alphaAttributionByStrategy(fromInstant, toExclusive));
        List<ProtectionRemovalSummary> protectionRemoval = buildProtectionRemoval(
                repository.protectionRemovalByStrategy(fromInstant, toExclusive));
        List<AlphaScoredStrategy> alphaScored = scoreStrategies(attributions, protectionRemoval);
        List<ConfidenceValidationBucket> confidenceGlobal = buildConfidenceValidation(
                repository.confidenceValidationBuckets(fromInstant, toExclusive, null));
        List<ConfidenceValidationBucket> confidencePostV8 = v8Cutoff != null
                ? buildConfidenceValidation(repository.confidenceValidationBucketsPostV8(fromInstant, toExclusive, v8Cutoff))
                : List.of();
        List<CapitalAllocationVerdict> capital = buildCapitalVerdicts(alphaScored, attributions, protectionRemoval);
        List<RetirementCandidate> retirement = buildRetirementCandidates(attributions, protectionRemoval, alphaScored);
        ThirtyDayScorecard scorecard = buildThirtyDayScorecard(alphaScored, attributions, protectionRemoval, confidenceGlobal);

        PlatformAlphaVerdict platform = buildPlatformVerdict(attributions, protectionRemoval, confidenceGlobal, confidencePostV8);

        return new AlphaValidationReport(
                from.toString(),
                to.toString(),
                v8Cutoff != null ? v8Cutoff.toString() : null,
                attributions,
                protectionRemoval,
                alphaScored,
                confidenceGlobal,
                confidencePostV8,
                capital,
                retirement,
                scorecard,
                platform
        );
    }

    private List<AlphaAttribution> buildAttributions(List<Object[]> rows) {
        Map<String, AlphaAttribution> map = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String key = r[0].toString();
            long generated = toLong(r[1]);
            long targetHits = toLong(r[3]);
            long slHits = toLong(r[4]);
            double grossProfit = toDouble(r[12]);
            double grossLoss = toDouble(r[13]);
            long resolved = targetHits + slHits;
            double winRate = resolved == 0 ? 0 : (double) targetHits / resolved * 100.0;
            double profitFactor = grossLoss == 0 ? (grossProfit > 0 ? 99.0 : 0) : grossProfit / grossLoss;
            double avgPnl = toDouble(r[11]);
            map.put(key, new AlphaAttribution(
                    key,
                    generated,
                    toLong(r[2]),
                    targetHits,
                    slHits,
                    toLong(r[5]),
                    round(toDouble(r[6])),
                    round(toDouble(r[7]) * 100),
                    round(toDouble(r[8]) * 100),
                    round(toDouble(r[9])),
                    round(toDouble(r[10])),
                    round(avgPnl),
                    round(profitFactor),
                    round(winRate)
            ));
        }
        for (String s : ALPHA_SPRINT_STRATEGIES) {
            map.computeIfAbsent(s, k -> emptyAttribution(k));
        }
        return map.values().stream()
                .sorted(Comparator.comparingLong(AlphaAttribution::signalsGenerated).reversed())
                .toList();
    }

    private AlphaAttribution emptyAttribution(String key) {
        return new AlphaAttribution(key, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private List<ProtectionRemovalSummary> buildProtectionRemoval(List<Object[]> rows) {
        List<ProtectionRemovalSummary> out = new ArrayList<>();
        for (Object[] r : rows) {
            long protectedCount = toLong(r[1]);
            long wouldTarget = toLong(r[2]);
            long wouldStop = toLong(r[3]);
            long wouldOpen = toLong(r[4]);
            long wouldProfit = toLong(r[5]);
            long wouldLose = toLong(r[6]);
            double missedProfit = toDouble(r[7]);
            double savedLoss = toDouble(r[8]);
            double netImpact = round(savedLoss - missedProfit);
            out.add(new ProtectionRemovalSummary(
                    r[0].toString(),
                    protectedCount,
                    wouldTarget,
                    wouldStop,
                    wouldOpen,
                    wouldProfit,
                    wouldLose,
                    round(missedProfit),
                    round(savedLoss),
                    netImpact,
                    protectedCount == 0 ? 0 : round((double) wouldTarget / protectedCount * 100),
                    protectedCount == 0 ? 0 : round((double) wouldStop / protectedCount * 100)
            ));
        }
        return out;
    }

    private List<AlphaScoredStrategy> scoreStrategies(
            List<AlphaAttribution> attributions,
            List<ProtectionRemovalSummary> protection
    ) {
        Map<String, ProtectionRemovalSummary> protMap = protection.stream()
                .collect(Collectors.toMap(ProtectionRemovalSummary::strategyKey, p -> p, (a, b) -> a));
        List<AlphaScoredStrategy> scored = new ArrayList<>();
        for (AlphaAttribution a : attributions) {
            if (!ALPHA_SPRINT_STRATEGIES.contains(a.strategyKey()) && a.signalsGenerated() == 0) {
                continue;
            }
            ProtectionRemovalSummary p = protMap.get(a.strategyKey());
            double protectionPenalty = p != null && p.protectedTrades() > 0
                    ? Math.min(100, p.wouldHaveHitTargetPct()) : 0;
            double targetReach = a.signalsGenerated() == 0 ? 0
                    : (double) a.targetHits() / a.signalsGenerated() * 100;
            double maxDd = Math.abs(a.realizedPnl() < 0 ? a.realizedPnl() : 0);

            double alphaScore = computeAlphaScore(
                    a.expectancy(),
                    a.winRate(),
                    a.profitFactor(),
                    targetReach,
                    protectionPenalty,
                    a.avgConfidencePct(),
                    maxDd,
                    p != null ? p.netImpact() : 0
            );
            scored.add(new AlphaScoredStrategy(
                    a.strategyKey(),
                    round(alphaScore),
                    a.expectancy(),
                    a.winRate(),
                    a.profitFactor(),
                    round(maxDd),
                    p != null ? p.netImpact() : 0,
                    round(targetReach),
                    round(a.avgConfidencePct())
            ));
        }
        scored.sort(Comparator.comparingDouble(AlphaScoredStrategy::alphaScore).reversed());
        return scored;
    }

    private double computeAlphaScore(
            double expectancy,
            double winRate,
            double profitFactor,
            double targetReach,
            double protectionWouldTargetPct,
            double avgConfidence,
            double maxDrawdownProxy,
            double protectionNetImpact
    ) {
        double expComponent = Math.max(0, Math.min(30, expectancy * 10 + 15));
        double wrComponent = Math.max(0, Math.min(25, winRate * 0.25));
        double pfComponent = Math.max(0, Math.min(20, Math.min(profitFactor, 3) / 3 * 20));
        double targetComponent = Math.max(0, Math.min(15, targetReach * 0.15));
        double confComponent = Math.max(0, Math.min(10, avgConfidence * 0.1));
        double protPenalty = Math.min(25, protectionWouldTargetPct * 0.2 + (protectionNetImpact < 0 ? 10 : 0));
        double ddPenalty = Math.min(15, maxDrawdownProxy * 0.01);
        return Math.max(0, Math.min(100, expComponent + wrComponent + pfComponent + targetComponent + confComponent - protPenalty - ddPenalty));
    }

    private List<ConfidenceValidationBucket> buildConfidenceValidation(List<Object[]> rows) {
        List<ConfidenceValidationBucket> out = new ArrayList<>();
        for (Object[] r : rows) {
            long signals = toLong(r[1]);
            long wins = toLong(r[2]);
            long losses = toLong(r[3]);
            long targets = toLong(r[4]);
            long sl = toLong(r[5]);
            double grossProfit = toDouble(r[6]);
            double grossLoss = toDouble(r[7]);
            double avgPnl = toDouble(r[8]);
            long resolved = targets + sl;
            double winRate = resolved == 0 ? 0 : (double) targets / resolved * 100;
            double pf = grossLoss == 0 ? (grossProfit > 0 ? 99 : 0) : grossProfit / grossLoss;
            out.add(new ConfidenceValidationBucket(
                    r[0].toString(),
                    signals,
                    round(winRate),
                    round(pf),
                    round(avgPnl),
                    wins,
                    losses
            ));
        }
        return out;
    }

    private List<CapitalAllocationVerdict> buildCapitalVerdicts(
            List<AlphaScoredStrategy> scored,
            List<AlphaAttribution> attributions,
            List<ProtectionRemovalSummary> protection
    ) {
        Map<String, AlphaAttribution> attrMap = attributions.stream()
                .collect(Collectors.toMap(AlphaAttribution::strategyKey, a -> a, (x, y) -> x));
        Map<String, ProtectionRemovalSummary> protMap = protection.stream()
                .collect(Collectors.toMap(ProtectionRemovalSummary::strategyKey, p -> p, (x, y) -> x));

        List<CapitalAllocationVerdict> out = new ArrayList<>();
        for (String key : ALPHA_SPRINT_STRATEGIES) {
            AlphaAttribution a = attrMap.getOrDefault(key, emptyAttribution(key));
            AlphaScoredStrategy s = scored.stream().filter(x -> x.strategyKey().equals(key)).findFirst().orElse(null);
            ProtectionRemovalSummary p = protMap.get(key);
            CapitalTier tier = classifyCapital(a, s, p);
            String evidence = capitalEvidence(a, s, p, tier);
            out.add(new CapitalAllocationVerdict(key, tier.name(), evidence,
                    s != null ? s.alphaScore() : 0));
        }
        return out;
    }

    private CapitalTier classifyCapital(
            AlphaAttribution a,
            AlphaScoredStrategy s,
            ProtectionRemovalSummary p
    ) {
        if (a.signalsGenerated() < 10) {
            return CapitalTier.REJECT;
        }
        double protPct = a.signalsGenerated() == 0 ? 0
                : (double) a.protectionExits() / a.signalsGenerated() * 100;
        if (a.expectancy() < 0 && a.signalsGenerated() >= 20) {
            return CapitalTier.REJECT;
        }
        if (protPct > 55 || (p != null && p.netImpact() < 0 && p.protectedTrades() >= 5)) {
            return CapitalTier.PAPER_ONLY;
        }
        if (a.signalsGenerated() < 30 || a.winRate() < 40 || a.profitFactor() < 1.0) {
            return CapitalTier.PAPER_ONLY;
        }
        if (a.signalsGenerated() < 50 || a.winRate() < 45 || (s != null && s.alphaScore() < 45)) {
            return CapitalTier.LIMITED_LIVE;
        }
        if (s != null && s.alphaScore() >= 60 && a.profitFactor() >= 1.3 && a.expectancy() > 0 && protPct < 35) {
            if (a.signalsGenerated() >= 80 && a.profitFactor() >= 1.5 && a.targetHits() >= 5) {
                return CapitalTier.CAPITAL_READY;
            }
            return CapitalTier.LIVE_READY;
        }
        return CapitalTier.LIMITED_LIVE;
    }

    private String capitalEvidence(
            AlphaAttribution a,
            AlphaScoredStrategy s,
            ProtectionRemovalSummary p,
            CapitalTier tier
    ) {
        return String.format(Locale.US,
                "%s: %d signals, win %.1f%%, PF %.2f, expectancy %.2f, protection exits %d, "
                        + "alpha score %.1f, protection net impact %.2f.",
                tier.name(),
                a.signalsGenerated(),
                a.winRate(),
                a.profitFactor(),
                a.expectancy(),
                a.protectionExits(),
                s != null ? s.alphaScore() : 0,
                p != null ? p.netImpact() : 0);
    }

    private List<RetirementCandidate> buildRetirementCandidates(
            List<AlphaAttribution> attributions,
            List<ProtectionRemovalSummary> protection,
            List<AlphaScoredStrategy> scored
    ) {
        Map<String, ProtectionRemovalSummary> protMap = protection.stream()
                .collect(Collectors.toMap(ProtectionRemovalSummary::strategyKey, p -> p, (a, b) -> a));
        Map<String, AlphaScoredStrategy> scoreMap = scored.stream()
                .collect(Collectors.toMap(AlphaScoredStrategy::strategyKey, s -> s, (a, b) -> a));

        List<RetirementCandidate> out = new ArrayList<>();
        for (AlphaAttribution a : attributions) {
            if (a.signalsGenerated() < 50) {
                continue;
            }
            double protPct = (double) a.protectionExits() / a.signalsGenerated() * 100;
            boolean noEdge = a.targetHits() == 0 && a.winRate() < 40;
            boolean negExp = a.expectancy() < 0;
            boolean excessProt = protPct > 50;
            if (noEdge || negExp || excessProt) {
                ProtectionRemovalSummary p = protMap.get(a.strategyKey());
                AlphaScoredStrategy s = scoreMap.get(a.strategyKey());
                List<String> reasons = new ArrayList<>();
                if (noEdge) {
                    reasons.add("zero or negligible target hits with win rate " + round(a.winRate()) + "%");
                }
                if (negExp) {
                    reasons.add("negative expectancy " + a.expectancy());
                }
                if (excessProt) {
                    reasons.add("protection exit rate " + round(protPct) + "%");
                }
                out.add(new RetirementCandidate(
                        a.strategyKey(),
                        a.signalsGenerated(),
                        a.targetHits(),
                        a.expectancy(),
                        round(protPct),
                        s != null ? s.alphaScore() : 0,
                        p != null ? p.netImpact() : 0,
                        String.join("; ", reasons),
                        true
                ));
            }
        }
        return out;
    }

    private ThirtyDayScorecard buildThirtyDayScorecard(
            List<AlphaScoredStrategy> scored,
            List<AlphaAttribution> attributions,
            List<ProtectionRemovalSummary> protection,
            List<ConfidenceValidationBucket> confidence
    ) {
        String top = scored.isEmpty() ? "—" : scored.get(0).strategyKey();
        String worst = scored.isEmpty() ? "—" : scored.get(scored.size() - 1).strategyKey();
        String highestAlpha = top;
        String lowestAlpha = worst;

        String mostReliable = attributions.stream()
                .filter(a -> a.signalsGenerated() >= 10)
                .max(Comparator.comparingDouble(AlphaAttribution::winRate))
                .map(AlphaAttribution::strategyKey)
                .orElse("—");

        String mostProtSensitive = protection.stream()
                .max(Comparator.comparingLong(ProtectionRemovalSummary::protectedTrades))
                .map(ProtectionRemovalSummary::strategyKey)
                .orElse("—");

        String mostConfAccurate = confidence.stream()
                .filter(c -> !"NULL".equals(c.bucket()) && c.signals() >= 5)
                .max(Comparator.comparingDouble(ConfidenceValidationBucket::winRate))
                .map(c -> c.bucket() + " bucket")
                .orElse("insufficient non-null confidence data");

        return new ThirtyDayScorecard(
                top, worst, highestAlpha, lowestAlpha, mostReliable, mostProtSensitive, mostConfAccurate
        );
    }

    private PlatformAlphaVerdict buildPlatformVerdict(
            List<AlphaAttribution> attributions,
            List<ProtectionRemovalSummary> protection,
            List<ConfidenceValidationBucket> confidenceGlobal,
            List<ConfidenceValidationBucket> confidencePostV8
    ) {
        long totalSignals = attributions.stream().mapToLong(AlphaAttribution::signalsGenerated).sum();
        long totalTargets = attributions.stream().mapToLong(AlphaAttribution::targetHits).sum();
        double totalRealized = attributions.stream().mapToDouble(AlphaAttribution::realizedPnl).sum();
        double netProtImpact = protection.stream().mapToDouble(ProtectionRemovalSummary::netImpact).sum();
        boolean maskingAlpha = netProtImpact < 0 && totalTargets < totalSignals * 0.05;

        boolean confPredicts = confidencePostV8.stream()
                .filter(c -> !"NULL".equals(c.bucket()))
                .count() >= 2
                && confidencePostV8.stream()
                .filter(c -> "81-100".equals(c.bucket()))
                .findFirst()
                .map(high -> confidencePostV8.stream()
                        .filter(c -> "0-20".equals(c.bucket()) || "21-40".equals(c.bucket()))
                        .allMatch(low -> high.winRate() > low.winRate()))
                .orElse(false);

        boolean genuineEdge = attributions.stream()
                .anyMatch(a -> a.expectancy() > 0 && a.profitFactor() >= 1.2 && a.targetHits() >= 3);

        String summary;
        if (totalSignals == 0) {
            summary = "No production signals in window — cannot validate alpha.";
        } else if (!genuineEdge) {
            summary = maskingAlpha
                    ? "No demonstrated edge; protection exits likely masking latent target hits (negative net protection impact)."
                    : "No demonstrated edge in production outcomes (insufficient target resolution and expectancy).";
        } else {
            summary = "At least one strategy shows positive expectancy with resolved targets in production data.";
        }

        return new PlatformAlphaVerdict(
                genuineEdge,
                maskingAlpha,
                confPredicts,
                round(totalRealized),
                round(netProtImpact),
                summary
        );
    }

    private static long toLong(Object o) {
        if (o == null) {
            return 0L;
        }
        return o instanceof Number n ? n.longValue() : Long.parseLong(o.toString());
    }

    private static double toDouble(Object o) {
        if (o == null) {
            return 0.0;
        }
        if (o instanceof BigDecimal bd) {
            return bd.doubleValue();
        }
        return o instanceof Number n ? n.doubleValue() : Double.parseDouble(o.toString());
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public enum CapitalTier {
        REJECT,
        PAPER_ONLY,
        LIMITED_LIVE,
        LIVE_READY,
        CAPITAL_READY
    }

    public record AlphaValidationReport(
            String fromDate,
            String toDate,
            String v8CutoffInstant,
            List<AlphaAttribution> alphaAttribution,
            List<ProtectionRemovalSummary> protectionRemovalAnalysis,
            List<AlphaScoredStrategy> alphaScores,
            List<ConfidenceValidationBucket> confidenceValidation,
            List<ConfidenceValidationBucket> confidenceValidationPostV8,
            List<CapitalAllocationVerdict> capitalAllocation,
            List<RetirementCandidate> retirementCandidates,
            ThirtyDayScorecard thirtyDayScorecard,
            PlatformAlphaVerdict platformVerdict
    ) {
    }

    public record AlphaAttribution(
            String strategyKey,
            long signalsGenerated,
            long signalsExecuted,
            long targetHits,
            long stopHits,
            long protectionExits,
            double avgHoldSeconds,
            double avgConfidencePct,
            double avgProbabilityPct,
            double realizedPnl,
            double expectedPnl,
            double expectancy,
            double profitFactor,
            double winRate
    ) {
    }

    public record ProtectionRemovalSummary(
            String strategyKey,
            long protectedTrades,
            long wouldHitTarget,
            long wouldHitStop,
            long wouldRemainOpen,
            long wouldBeProfitable,
            long wouldBeLosing,
            double missedProfit,
            double savedLoss,
            double netImpact,
            double wouldHaveHitTargetPct,
            double wouldHaveHitStopPct
    ) {
    }

    public record AlphaScoredStrategy(
            String strategyKey,
            double alphaScore,
            double expectancy,
            double winRate,
            double profitFactor,
            double maxDrawdownProxy,
            double protectionNetImpact,
            double targetReachRate,
            double avgConfidencePct
    ) {
    }

    public record ConfidenceValidationBucket(
            String bucket,
            long signals,
            double winRate,
            double profitFactor,
            double expectancy,
            long wins,
            long losses
    ) {
    }

    public record CapitalAllocationVerdict(
            String strategyKey,
            String tier,
            String evidence,
            double alphaScore
    ) {
    }

    public record RetirementCandidate(
            String strategyKey,
            long signalsGenerated,
            long targetHits,
            double expectancy,
            double protectionExitPct,
            double alphaScore,
            double protectionNetImpact,
            String evidence,
            boolean recommendRetirement
    ) {
    }

    public record ThirtyDayScorecard(
            String topStrategy,
            String worstStrategy,
            String highestAlpha,
            String lowestAlpha,
            String mostReliable,
            String mostProtectionSensitive,
            String mostConfidenceAccurate
    ) {
    }

    public record PlatformAlphaVerdict(
            boolean genuineEdgeDetected,
            boolean protectionMaskingAlpha,
            boolean confidencePredictsSuccessPostV8,
            double totalRealizedPnl,
            double totalProtectionNetImpact,
            String summary
    ) {
    }
}
