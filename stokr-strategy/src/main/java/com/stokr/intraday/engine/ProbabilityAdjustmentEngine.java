package com.stokr.intraday.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Adjusts base probabilities from historical_win_rates based on current market conditions
 * Factors:
 * - Market regime adjustment
 * - Time-of-day adjustment
 * - Sector momentum
 * - Recent performance (last 5 setups of this type)
 */
@Service
@Slf4j
public class ProbabilityAdjustmentEngine {

    private final MarketRegimeDetector marketRegimeDetector;

    public ProbabilityAdjustmentEngine(MarketRegimeDetector marketRegimeDetector) {
        this.marketRegimeDetector = marketRegimeDetector;
    }

    /**
     * Adjust base probability with all factors
     *
     * @param baseProbability Win rate from historical data (0.5 to 0.9)
     * @param marketRegime Current market regime
     * @param setupType Type of setup
     * @param sectorMomentum Sector performance (-0.10 to +0.10)
     * @param recentPerformance Win rate of last 5 setups of this type
     * @param hourOfDay Current hour (9-15)
     * @return Adjusted probability (0.30 to 0.95, capped)
     */
    public BigDecimal adjustProbability(
            BigDecimal baseProbability,
            MarketRegimeDetector.MarketRegime marketRegime,
            String setupType,
            BigDecimal sectorMomentum,
            BigDecimal recentPerformance,
            Integer hourOfDay) {

        BigDecimal adjusted = baseProbability.multiply(BigDecimal.ONE);

        // 1. Market regime adjustment (??15%)
        BigDecimal regimeAdjustment = marketRegimeDetector.getRegimeAdjustment(
                marketRegime, setupType);
        BigDecimal regimeFactor = BigDecimal.ONE.add(
                regimeAdjustment.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        adjusted = adjusted.multiply(regimeFactor);

        // 2. Time-of-day adjustment (??10%)
        BigDecimal timeAdjustment = getTimeOfDayAdjustment(setupType, hourOfDay);
        BigDecimal timeFactor = BigDecimal.ONE.add(
                timeAdjustment.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        adjusted = adjusted.multiply(timeFactor);

        // 3. Sector momentum adjustment (??5%)
        BigDecimal sectorAdjustment = getSectorMomentumAdjustment(sectorMomentum);
        BigDecimal sectorFactor = BigDecimal.ONE.add(
                sectorAdjustment.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        adjusted = adjusted.multiply(sectorFactor);

        // 4. Recent performance adjustment (??10%)
        if (recentPerformance != null) {
            BigDecimal recentAdjustment = getRecentPerformanceAdjustment(baseProbability, recentPerformance);
            BigDecimal recentFactor = BigDecimal.ONE.add(
                    recentAdjustment.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
            adjusted = adjusted.multiply(recentFactor);
        }

        // Cap between 0.30 and 0.95 (minimum 30% win rate, maximum 95%)
        adjusted = adjusted.max(BigDecimal.valueOf(0.30))
                .min(BigDecimal.valueOf(0.95));

        log.debug("probability.adjusted base={} regime_adj={} time_adj={} recent_adj={} final={}",
                baseProbability, regimeAdjustment, timeAdjustment,
                recentPerformance, adjusted);

        return adjusted;
    }

    /**
     * Time-of-day adjustments for different setups
     * Most setups work best in first 1-2 hours
     */
    private BigDecimal getTimeOfDayAdjustment(String setupType, Integer hourOfDay) {
        if (hourOfDay == null) {
            return BigDecimal.ZERO;
        }

        return switch (setupType) {
            case "gap_fill" -> switch (hourOfDay) {
                case 9 -> BigDecimal.valueOf(10);  // Best at open
                case 10 -> BigDecimal.valueOf(8);
                case 11 -> BigDecimal.ZERO;
                case 12 -> BigDecimal.valueOf(-5);
                case 13, 14, 15 -> BigDecimal.valueOf(-10);
                default -> BigDecimal.ZERO;
            };
            case "vwap_bounce" -> switch (hourOfDay) {
                case 9 -> BigDecimal.valueOf(5);
                case 10, 11 -> BigDecimal.valueOf(10);
                case 12 -> BigDecimal.valueOf(5);
                case 13, 14, 15 -> BigDecimal.valueOf(-8);
                default -> BigDecimal.ZERO;
            };
            case "sector_laggard" -> switch (hourOfDay) {
                case 10, 11, 12 -> BigDecimal.valueOf(5);
                case 13, 14, 15 -> BigDecimal.valueOf(-5);
                default -> BigDecimal.ZERO;
            };
            case "early_breakout" -> switch (hourOfDay) {
                case 9 -> BigDecimal.valueOf(15); // Only happens in first hour
                case 10 -> BigDecimal.valueOf(10);
                default -> BigDecimal.valueOf(-100); // Not applicable
            };
            default -> BigDecimal.ZERO;
        };
    }

    /**
     * Sector momentum adjustment
     * Positive momentum: +3% to +5%
     * Negative momentum: -3% to -5%
     */
    private BigDecimal getSectorMomentumAdjustment(BigDecimal sectorMomentum) {
        if (sectorMomentum == null || sectorMomentum.signum() == 0) {
            return BigDecimal.ZERO;
        }

        // Scale momentum (-0.10 to +0.10) to adjustment (-5 to +5)
        return sectorMomentum.multiply(BigDecimal.valueOf(50))
                .max(BigDecimal.valueOf(-5))
                .min(BigDecimal.valueOf(5));
    }

    /**
     * Recent performance adjustment
     * If recent > base: boost (positive divergence)
     * If recent < base: reduce (negative divergence)
     * Max ??10%
     */
    private BigDecimal getRecentPerformanceAdjustment(BigDecimal baseProbability, BigDecimal recentPerformance) {
        if (recentPerformance == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal divergence = recentPerformance.subtract(baseProbability);
        return divergence.multiply(BigDecimal.valueOf(100))
                .max(BigDecimal.valueOf(-10))
                .min(BigDecimal.valueOf(10));
    }

    /**
     * Calculate expected value for a setup
     * E[V] = prob * avg_win - (1 - prob) * abs(avg_loss)
     */
    public BigDecimal calculateExpectedValue(
            BigDecimal probability,
            BigDecimal avgWinPercent,
            BigDecimal avgLossPercent) {

        if (probability == null || avgWinPercent == null || avgLossPercent == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal winComponent = probability.multiply(avgWinPercent);
        BigDecimal lossComponent = BigDecimal.ONE.subtract(probability)
                .multiply(avgLossPercent.abs());

        return winComponent.subtract(lossComponent);
    }

    /**
     * Determine confidence level based on sample size
     */
    public String getConfidenceLevel(Integer sampleSize) {
        if (sampleSize == null || sampleSize == 0) {
            return "LOW";
        } else if (sampleSize >= 100) {
            return "HIGH";
        } else if (sampleSize >= 50) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }
}
