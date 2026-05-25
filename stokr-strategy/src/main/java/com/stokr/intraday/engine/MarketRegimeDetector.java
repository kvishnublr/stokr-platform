package com.stokr.intraday.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Detects current market regime based on NIFTY 50 price action and technicals
 * 5 regimes:
 * - TRENDING_UP: Strong uptrend, positive momentum
 * - TRENDING_DOWN: Strong downtrend, negative momentum
 * - CHOPPY: Range-bound, no clear direction
 * - VOLATILE: High volatility, unclear direction
 * - QUIET: Low volatility, low volume
 */
@Service
@Slf4j
public class MarketRegimeDetector {

    /**
     * Enum for market regimes
     */
    public enum MarketRegime {
        TRENDING_UP(1.15),      // Gap fill: +15%, VWAP: +15%, Sector Laggard: -5%, Early BO: +10%
        TRENDING_DOWN(0.85),    // All setups perform worse
        CHOPPY(0.90),           // Slightly worse performance
        VOLATILE(0.95),         // Slightly worse but still viable
        QUIET(1.05);            // Slightly better (less noise)

        public final double adjustmentFactor; // Probability adjustment multiplier

        MarketRegime(double adjustmentFactor) {
            this.adjustmentFactor = adjustmentFactor;
        }
    }

    /**
     * Market regime data
     */
    public static class RegimeSnapshot {
        public MarketRegime regime;
        public BigDecimal nifty50Price;
        public BigDecimal nifty50Change; // % change from open
        public BigDecimal trendScore;    // -1.0 to +1.0
        public BigDecimal momentum;      // EMA-based momentum
        public BigDecimal volatilityRatio; // ATR / price
        public BigDecimal volumeRatio;   // Current / average
        public long timestamp;

        public RegimeSnapshot(MarketRegime regime, BigDecimal nifty50Price, BigDecimal nifty50Change,
                             BigDecimal trendScore, BigDecimal momentum, BigDecimal volatilityRatio,
                             BigDecimal volumeRatio) {
            this.regime = regime;
            this.nifty50Price = nifty50Price;
            this.nifty50Change = nifty50Change;
            this.trendScore = trendScore;
            this.momentum = momentum;
            this.volatilityRatio = volatilityRatio;
            this.volumeRatio = volumeRatio;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Detect market regime based on inputs
     *
     * @param nifty50Price Current NIFTY 50 price
     * @param nifty50Change % change from open
     * @param momentum EMA-based momentum (9-period vs 21-period)
     * @param volatilityRatio ATR14 / current price
     * @param volumeRatio Current volume / 20-day average volume
     * @return Market regime with snapshot
     */
    public RegimeSnapshot detectRegime(
            BigDecimal nifty50Price,
            BigDecimal nifty50Change,
            BigDecimal momentum,
            BigDecimal volatilityRatio,
            BigDecimal volumeRatio) {

        // Calculate trend score (-1 to +1)
        BigDecimal trendScore = calculateTrendScore(nifty50Change, momentum);

        // Determine regime
        MarketRegime regime = determineRegime(trendScore, volatilityRatio, volumeRatio, momentum);

        log.info("market.regime.detected regime={} nifty50_change={} volatility={} volume_ratio={}",
                regime, nifty50Change, volatilityRatio, volumeRatio);

        return new RegimeSnapshot(regime, nifty50Price, nifty50Change, trendScore, momentum, volatilityRatio, volumeRatio);
    }

    /**
     * Calculate trend score from price change and momentum
     */
    private BigDecimal calculateTrendScore(BigDecimal nifty50Change, BigDecimal momentum) {
        // Trend score: average of normalized change and momentum
        BigDecimal normalizedChange = nifty50Change.divide(BigDecimal.valueOf(5), 4, RoundingMode.HALF_UP)
                .max(BigDecimal.valueOf(-1.0))
                .min(BigDecimal.valueOf(1.0));

        BigDecimal normalizedMomentum = momentum.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP)
                .max(BigDecimal.valueOf(-1.0))
                .min(BigDecimal.valueOf(1.0));

        return normalizedChange.add(normalizedMomentum)
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    /**
     * Determine regime based on trend, volatility, and volume
     */
    private MarketRegime determineRegime(BigDecimal trendScore, BigDecimal volatilityRatio,
                                        BigDecimal volumeRatio, BigDecimal momentum) {
        // Volatility levels
        boolean isHighVolatility = volatilityRatio.compareTo(BigDecimal.valueOf(0.025)) > 0;
        boolean isLowVolatility = volatilityRatio.compareTo(BigDecimal.valueOf(0.010)) < 0;

        // Volume levels
        boolean isHighVolume = volumeRatio.compareTo(BigDecimal.valueOf(1.2)) > 0;
        boolean isLowVolume = volumeRatio.compareTo(BigDecimal.valueOf(0.8)) < 0;

        // Trend strength
        boolean isStrongTrend = trendScore.abs().compareTo(BigDecimal.valueOf(0.5)) > 0;
        boolean isMildTrend = trendScore.abs().compareTo(BigDecimal.valueOf(0.2)) > 0;

        // Logic
        if (isLowVolume && isLowVolatility) {
            return MarketRegime.QUIET;
        }

        if (isHighVolatility && !isStrongTrend) {
            return MarketRegime.VOLATILE;
        }

        if (!isMildTrend && isHighVolume) {
            return MarketRegime.CHOPPY;
        }

        // Trending
        if (trendScore.compareTo(BigDecimal.valueOf(0.3)) > 0) {
            return MarketRegime.TRENDING_UP;
        }

        if (trendScore.compareTo(BigDecimal.valueOf(-0.3)) < 0) {
            return MarketRegime.TRENDING_DOWN;
        }

        // Default to choppy if unclear
        return MarketRegime.CHOPPY;
    }

    /**
     * Get probability adjustment for a setup in given regime
     */
    public BigDecimal getRegimeAdjustment(MarketRegime regime, String setupType) {
        return switch (regime) {
            case TRENDING_UP -> switch (setupType) {
                case "gap_fill" -> BigDecimal.valueOf(15);
                case "vwap_bounce" -> BigDecimal.valueOf(15);
                case "sector_laggard" -> BigDecimal.valueOf(-5);
                case "early_breakout" -> BigDecimal.valueOf(10);
                default -> BigDecimal.ZERO;
            };
            case TRENDING_DOWN -> switch (setupType) {
                case "gap_fill" -> BigDecimal.valueOf(-15);
                case "vwap_bounce" -> BigDecimal.valueOf(-10);
                case "sector_laggard" -> BigDecimal.valueOf(5);
                case "early_breakout" -> BigDecimal.valueOf(-15);
                default -> BigDecimal.ZERO;
            };
            case CHOPPY -> BigDecimal.valueOf(-10);
            case VOLATILE -> BigDecimal.valueOf(-5);
            case QUIET -> switch (setupType) {
                case "gap_fill" -> BigDecimal.valueOf(5);
                case "vwap_bounce" -> BigDecimal.valueOf(10);
                default -> BigDecimal.ZERO;
            };
        };
    }
}
