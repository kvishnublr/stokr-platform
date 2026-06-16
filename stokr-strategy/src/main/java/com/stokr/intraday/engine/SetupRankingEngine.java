package com.stokr.intraday.engine;

import com.stokr.intraday.domain.CurrentSetup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Ranks detected setups by quality score
 *
 * Quality Score Calculation (0-100):
 * - 40% probability (risk-adjusted)
 * - 30% risk/reward ratio
 * - 15% confidence level (based on sample size)
 * - 15% expected value
 */
@Service
@Slf4j
public class SetupRankingEngine {

    private final ProbabilityAdjustmentEngine probabilityEngine;

    public SetupRankingEngine(ProbabilityAdjustmentEngine probabilityEngine) {
        this.probabilityEngine = probabilityEngine;
    }

    /**
     * Calculate quality score for a setup (0-100)
     *
     * Scoring breakdown:
     * - Probability (40%): Normalized from 0.30-0.95 range to 0-100
     * - Risk/Reward (30%): 1.5 maps to 0%, 3.0 maps to 100%
     * - Confidence (15%): HIGH=100, MEDIUM=70, LOW=40
     * - Expected Value (15%): Positive EV boosts score, negative reduces it
     *
     * @param setup Detected setup
     * @param probability Adjusted probability from market conditions
     * @param avgWinPercent Average win size for this setup type
     * @param avgLossPercent Average loss size for this setup type
     * @return Quality score 0-100
     */
    public BigDecimal calculateQualityScore(
            CurrentSetup setup,
            BigDecimal probability,
            BigDecimal avgWinPercent,
            BigDecimal avgLossPercent,
            Integer sampleSize) {

        if (setup == null || probability == null) {
            return BigDecimal.ZERO;
        }

        // 1. Probability component (40%): Normalize from 0.30-0.95 to 0-100
        // Formula: (prob - 0.30) / (0.95 - 0.30) * 100
        BigDecimal probNormalized = probability
                .subtract(BigDecimal.valueOf(0.30))
                .divide(BigDecimal.valueOf(0.65), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .max(BigDecimal.ZERO)
                .min(BigDecimal.valueOf(100));
        BigDecimal probScore = probNormalized.multiply(BigDecimal.valueOf(0.40));

        // 2. Risk/Reward component (30%): Scale ratio to 0-100
        // 1.5x maps to 0%, 3.0x maps to 100%
        BigDecimal rrScore = BigDecimal.ZERO;
        if (setup.getRiskRewardRatio() != null) {
            BigDecimal rrNormalized = setup.getRiskRewardRatio()
                    .subtract(BigDecimal.valueOf(1.5))
                    .divide(BigDecimal.valueOf(1.5), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .max(BigDecimal.ZERO)
                    .min(BigDecimal.valueOf(100));
            rrScore = rrNormalized.multiply(BigDecimal.valueOf(0.30));
        }

        // 3. Confidence component (15%): Based on sample size
        String confidenceLevel = probabilityEngine.getConfidenceLevel(sampleSize);
        BigDecimal confidenceScore = switch (confidenceLevel) {
            case "HIGH" -> BigDecimal.valueOf(100);
            case "MEDIUM" -> BigDecimal.valueOf(70);
            default -> BigDecimal.valueOf(40);
        };
        BigDecimal confidenceWeighted = confidenceScore.multiply(BigDecimal.valueOf(0.15));

        // 4. Expected Value component (15%): Positive EV boosts, negative reduces
        BigDecimal evScore = BigDecimal.ZERO;
        if (avgWinPercent != null && avgLossPercent != null) {
            BigDecimal expectedValue = probabilityEngine.calculateExpectedValue(
                    probability, avgWinPercent, avgLossPercent);
            // EV from -1% to +5% maps to 0-100
            // Formula: (ev + 0.01) / 0.06 * 100
            evScore = expectedValue
                    .add(BigDecimal.valueOf(0.01))
                    .divide(BigDecimal.valueOf(0.06), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .max(BigDecimal.ZERO)
                    .min(BigDecimal.valueOf(100))
                    .multiply(BigDecimal.valueOf(0.15));
        }

        // Total score
        BigDecimal totalScore = probScore
                .add(rrScore)
                .add(confidenceWeighted)
                .add(evScore);

        return totalScore.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Rank setups by quality score (highest first)
     *
     * @param setups List of detected setups
     * @return Sorted list by quality score descending
     */
    public List<CurrentSetup> rankSetups(List<CurrentSetup> setups) {
        if (setups == null || setups.isEmpty()) {
            return List.of();
        }

        return setups.stream()
                .filter(setup -> setup.getQualityScore() != null &&
                        setup.getQualityScore().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(CurrentSetup::getQualityScore).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Get top N setups by quality score
     *
     * @param setups All detected setups
     * @param limit Number of top setups to return
     * @return Top N setups
     */
    public List<CurrentSetup> getTopSetups(List<CurrentSetup> setups, int limit) {
        return rankSetups(setups).stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Filter setups by minimum quality score
     *
     * @param setups All detected setups
     * @param minScore Minimum quality score (0-100)
     * @return Setups meeting minimum score
     */
    public List<CurrentSetup> filterByMinimumQuality(List<CurrentSetup> setups, BigDecimal minScore) {
        return setups.stream()
                .filter(setup -> setup.getQualityScore() != null &&
                        setup.getQualityScore().compareTo(minScore) >= 0)
                .sorted(Comparator.comparing(CurrentSetup::getQualityScore).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Filter setups by setup type and rank
     *
     * @param setups All detected setups
     * @param setupType Type to filter (gap_fill, vwap_bounce, etc.)
     * @return Setups of given type, ranked by quality
     */
    public List<CurrentSetup> filterByType(List<CurrentSetup> setups, String setupType) {
        return setups.stream()
                .filter(setup -> setup.getSetupType() != null &&
                        setup.getSetupType().equals(setupType))
                .sorted(Comparator.comparing(CurrentSetup::getQualityScore).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Get setups by confidence level
     *
     * @param setups All detected setups
     * @param confidenceLevel Desired level (HIGH, MEDIUM, LOW)
     * @return Matching setups
     */
    public List<CurrentSetup> filterByConfidence(List<CurrentSetup> setups, String confidenceLevel) {
        return setups.stream()
                .filter(setup -> setup.getConfidenceLevel() != null &&
                        setup.getConfidenceLevel().equals(confidenceLevel))
                .sorted(Comparator.comparing(CurrentSetup::getQualityScore).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Log ranking summary for debugging
     */
    public void logRankingSummary(List<CurrentSetup> rankedSetups) {
        if (rankedSetups.isEmpty()) {
            log.info("ranking.summary no_setups_detected");
            return;
        }

        log.info("ranking.summary count={} top_score={} avg_score={}",
                rankedSetups.size(),
                rankedSetups.get(0).getQualityScore(),
                rankedSetups.stream()
                        .map(CurrentSetup::getQualityScore)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(rankedSetups.size()), 2, RoundingMode.HALF_UP)
        );

        rankedSetups.stream()
                .limit(5)
                .forEach(setup -> log.debug("ranking.top stock={} type={} score={} prob={} rr={}",
                        setup.getStockId(),
                        setup.getSetupType(),
                        setup.getQualityScore(),
                        setup.getAdjustedProbability(),
                        setup.getRiskRewardRatio()
                ));
    }
}
