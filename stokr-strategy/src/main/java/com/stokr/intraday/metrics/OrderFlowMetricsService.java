package com.stokr.intraday.metrics;

import com.stokr.intraday.metrics.domain.OrderFlowSnapshot;
import com.stokr.intraday.metrics.dto.OrderFlowSignalEnhancement;
import com.stokr.intraday.metrics.repository.OrderFlowSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderFlowMetricsService {

    private final OrderFlowSnapshotRepository snapshotRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${stokr.orderflow.enhancement-enabled:false}")
    private boolean enhancementEnabled;

    @Value("${stokr.orderflow.confidence-adjustment-factor:0.5}")
    private double confidenceAdjustmentFactor;

    /**
     * Get current order flow metrics for a symbol
     * Called by SetupDetectionService to enhance pattern signals
     *
     * Returns OrderFlowSignalEnhancement with:
     * - Buy/sell pressure scores
     * - Liquidity assessment
     * - Recommendation (proceed, reduce size, skip)
     * - Confidence multiplier
     */
    public OrderFlowSignalEnhancement getOrderFlowSignal(String symbol) {
        try {
            // Try to get real-time from Redis first (< 100ms latency)
            OrderFlowSnapshot latest = getLatestFromRedis(symbol);

            // If not in Redis, query database
            if (latest == null) {
                latest = snapshotRepository.findLatestBySymbol(symbol)
                    .orElse(null);
            }

            if (latest == null || !latest.getIsValid()) {
                return OrderFlowSignalEnhancement.noData(symbol);
            }

            // Build enhancement signal
            return OrderFlowSignalEnhancement.builder()
                .symbol(symbol)
                .timestamp(latest.getTimestamp())
                .bidAskRatio(latest.getBidAskRatio())
                .buyerPressureScore(latest.getBuyerPressureScore())
                .sellerPressureScore(latest.getSellerPressureScore())
                .liquidityScore(latest.getLiquidityScore())
                .spread(latest.getSpread())
                .spreadPct(latest.getSpreadPct())
                .bidVolume(latest.getBidVolume())
                .askVolume(latest.getAskVolume())
                .imbalance(latest.getImbalance())
                .pressureType(latest.getPressureType())
                .cumulativeBidDepth(latest.getCumulativeBid10Levels())
                .cumulativeAskDepth(latest.getCumulativeAsk10Levels())
                .buyerInitiatedPct(latest.getBuyerInitiatedPct() != null ?
                    latest.getBuyerInitiatedPct().intValue() : null)
                .sellerInitiatedPct(latest.getSellerInitiatedPct() != null ?
                    latest.getSellerInitiatedPct().intValue() : null)
                .recommendation(generateRecommendation(latest))
                .confidence(calculateConfidence(latest))
                .shouldEnhanceConfidence(shouldEnhanceConfidence(latest))
                .shouldReduceConfidence(shouldReduceConfidence(latest))
                .shouldSkip(shouldSkip(latest))
                .isValid(true)
                .build();

        } catch (Exception ex) {
            log.error("orderflow.metrics_failed symbol={} {}", symbol, ex.getMessage());
            return OrderFlowSignalEnhancement.error(symbol);
        }
    }

    /**
     * Get latest snapshot from Redis with fallback to DB
     */
    private OrderFlowSnapshot getLatestFromRedis(String symbol) {
        try {
            // In production, would deserialize from Redis
            // For now, return null to use DB query
            return null;
        } catch (Exception ex) {
            log.trace("orderflow.redis_miss symbol={}", symbol);
            return null;
        }
    }

    /**
     * Generate actionable recommendation based on order flow
     *
     * STRONG_BUY_PRESSURE: Bid/Ask > 1.3, Buyer score > 70, Liquidity > 70
     * BUY_PRESSURE: Bid/Ask > 1.1, Buyer score > 55, Liquidity > 60
     * NEUTRAL: Balanced
     * WEAK_SELL_PRESSURE: Seller > 55, Liquidity > 60
     * SELL_PRESSURE: Seller > 70, Liquidity > 70
     * POOR_LIQUIDITY_SKIP: Liquidity < 40
     */
    private String generateRecommendation(OrderFlowSnapshot snapshot) {
        int buyerScore = snapshot.getBuyerPressureScore();
        int sellerScore = snapshot.getSellerPressureScore();
        int liquidityScore = snapshot.getLiquidityScore();
        BigDecimal ratio = snapshot.getBidAskRatio();

        // Check liquidity first (hard blocker)
        if (liquidityScore < 40) {
            return "POOR_LIQUIDITY_SKIP";
        }

        // Strong signals
        if (buyerScore > 70 && ratio.compareTo(BigDecimal.valueOf(1.3)) > 0
            && liquidityScore > 70) {
            return "STRONG_BUY_PRESSURE";
        }

        if (sellerScore > 70 && ratio.compareTo(BigDecimal.valueOf(0.7)) < 0
            && liquidityScore > 70) {
            return "STRONG_SELL_PRESSURE";
        }

        // Moderate signals
        if (buyerScore > 55 && ratio.compareTo(BigDecimal.valueOf(1.1)) > 0
            && liquidityScore > 60) {
            return "BUY_PRESSURE";
        }

        if (sellerScore > 55 && ratio.compareTo(BigDecimal.valueOf(0.9)) < 0
            && liquidityScore > 60) {
            return "SELL_PRESSURE";
        }

        // Weak signals
        if (buyerScore > 50 && liquidityScore > 50) {
            return "WEAK_BUY_PRESSURE";
        }

        if (sellerScore > 50 && liquidityScore > 50) {
            return "WEAK_SELL_PRESSURE";
        }

        return "NEUTRAL";
    }

    /**
     * Calculate confidence level (0-100)
     * How much to trust this order flow signal
     *
     * Confidence = function of:
     * - Strength of pressure (buyer/seller score)
     * - Liquidity score (more liquid = more reliable)
     * - Spread tightness (tight = more participants)
     * - Recency (must be < 1 second old)
     */
    private int calculateConfidence(OrderFlowSnapshot snapshot) {
        int confidence = 0;

        // Factor 1: Pressure strength (40 points max)
        int maxPressure = Math.max(
            snapshot.getBuyerPressureScore(),
            snapshot.getSellerPressureScore()
        );
        confidence += (int) (maxPressure * 0.4);

        // Factor 2: Liquidity score (30 points max)
        if (snapshot.getLiquidityScore() != null) {
            confidence += (int) (snapshot.getLiquidityScore() * 0.3);
        }

        // Factor 3: Spread tightness (20 points max)
        if (snapshot.getSpreadPct() != null) {
            BigDecimal spreadPct = snapshot.getSpreadPct();
            if (spreadPct.compareTo(BigDecimal.valueOf(0.02)) < 0) {
                confidence += 20;  // Ultra tight
            } else if (spreadPct.compareTo(BigDecimal.valueOf(0.05)) < 0) {
                confidence += 15;
            } else if (spreadPct.compareTo(BigDecimal.valueOf(0.10)) < 0) {
                confidence += 10;
            } else if (spreadPct.compareTo(BigDecimal.valueOf(0.20)) < 0) {
                confidence += 5;
            }
        }

        // Factor 4: Recency (10 points max)
        if (snapshot.getTimestamp() != null) {
            long ageMs = ChronoUnit.MILLIS.between(
                snapshot.getTimestamp(),
                Instant.now()
            );
            if (ageMs < 100) {
                confidence += 10;  // < 100ms old
            } else if (ageMs < 500) {
                confidence += 7;
            } else if (ageMs < 1000) {
                confidence += 3;
            }
        }

        return Math.min(100, Math.max(0, confidence));
    }

    /**
     * Should we enhance signal confidence?
     * YES if: strong pressure + good liquidity + tight spread
     */
    private boolean shouldEnhanceConfidence(OrderFlowSnapshot snapshot) {
        if (!enhancementEnabled) {
            return false;
        }

        int maxPressure = Math.max(
            snapshot.getBuyerPressureScore(),
            snapshot.getSellerPressureScore()
        );

        return maxPressure > 65 &&
               snapshot.getLiquidityScore() > 65 &&
               snapshot.getSpreadPct().compareTo(BigDecimal.valueOf(0.10)) < 0;
    }

    /**
     * Should we reduce signal confidence?
     * YES if: poor liquidity OR weak pressure OR wide spread
     */
    private boolean shouldReduceConfidence(OrderFlowSnapshot snapshot) {
        if (!enhancementEnabled) {
            return false;
        }

        return snapshot.getLiquidityScore() < 50 ||
               (snapshot.getBuyerPressureScore() < 40 &&
                snapshot.getSellerPressureScore() < 40) ||
               snapshot.getSpreadPct().compareTo(BigDecimal.valueOf(0.30)) > 0;
    }

    /**
     * Should we skip the signal entirely?
     * YES if: very poor liquidity OR no pressure
     */
    private boolean shouldSkip(OrderFlowSnapshot snapshot) {
        return snapshot.getLiquidityScore() < 30 ||
               (snapshot.getBuyerPressureScore() < 20 &&
                snapshot.getSellerPressureScore() < 20);
    }

    /**
     * Analyze order flow trend over time window (last N seconds)
     * Used to detect momentum shifts
     */
    public Map<String, Object> analyzeTrend(String symbol, int secondsBack) {
        Map<String, Object> trend = new HashMap<>();

        Instant since = Instant.now().minus(secondsBack, ChronoUnit.SECONDS);
        List<OrderFlowSnapshot> snapshots = snapshotRepository
            .findBySymbolAndTimeRange(symbol, since, Instant.now());

        if (snapshots.isEmpty()) {
            trend.put("available", false);
            return trend;
        }

        // Analyze pressure trend
        List<Integer> buyerScores = snapshots.stream()
            .map(OrderFlowSnapshot::getBuyerPressureScore)
            .toList();

        double avgBuyerScore = buyerScores.stream()
            .mapToInt(Integer::intValue)
            .average()
            .orElse(0);

        double buyerTrend = (buyerScores.get(0) - buyerScores.get(buyerScores.size() - 1)) / 10.0;

        // Analyze liquidity trend
        List<Integer> liquidityScores = snapshots.stream()
            .map(OrderFlowSnapshot::getLiquidityScore)
            .toList();

        double avgLiquidity = liquidityScores.stream()
            .mapToInt(Integer::intValue)
            .average()
            .orElse(0);

        // Analyze bid/ask ratio trend
        List<BigDecimal> ratios = snapshots.stream()
            .map(OrderFlowSnapshot::getBidAskRatio)
            .toList();

        BigDecimal avgRatio = ratios.stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(ratios.size()), 4, java.math.RoundingMode.HALF_UP);

        trend.put("available", true);
        trend.put("period_seconds", secondsBack);
        trend.put("snapshots_analyzed", snapshots.size());
        trend.put("avg_buyer_pressure", (int) avgBuyerScore);
        trend.put("buyer_pressure_trend", buyerTrend > 0 ? "INCREASING" : "DECREASING");
        trend.put("avg_liquidity", (int) avgLiquidity);
        trend.put("avg_bid_ask_ratio", avgRatio.doubleValue());

        return trend;
    }

    /**
     * Get historical performance of order flow signals
     * Used for backtesting and validation
     */
    public Map<String, Object> getPerformanceMetrics(String symbol) {
        Map<String, Object> metrics = new HashMap<>();

        // Count signals by type (last 100 snapshots)
        List<OrderFlowSnapshot> recent = snapshotRepository
            .findBySymbolOrderByTimestampDesc(symbol,
                org.springframework.data.domain.PageRequest.of(0, 100))
            .getContent();

        if (recent.isEmpty()) {
            metrics.put("available", false);
            return metrics;
        }

        long buyPressureCount = recent.stream()
            .filter(s -> s.getBuyerPressureScore() > 60)
            .count();

        long sellPressureCount = recent.stream()
            .filter(s -> s.getSellerPressureScore() > 60)
            .count();

        long goodLiquidityCount = recent.stream()
            .filter(s -> s.getLiquidityScore() > 70)
            .count();

        metrics.put("available", true);
        metrics.put("snapshots_analyzed", recent.size());
        metrics.put("buy_pressure_signals", buyPressureCount);
        metrics.put("sell_pressure_signals", sellPressureCount);
        metrics.put("good_liquidity_percent", (goodLiquidityCount * 100) / recent.size());
        metrics.put("avg_spread_pct", recent.stream()
            .map(OrderFlowSnapshot::getSpreadPct)
            .filter(Objects::nonNull)
            .mapToDouble(BigDecimal::doubleValue)
            .average()
            .orElse(0));

        return metrics;
    }

    /**
     * Compare current order flow vs historical average (moving average)
     * Useful for anomaly detection
     */
    public boolean isOrderFlowAnomaly(String symbol) {
        OrderFlowSnapshot current = snapshotRepository.findLatestBySymbol(symbol)
            .orElse(null);

        if (current == null) {
            return false;
        }

        // Get last 20 snapshots for baseline
        List<OrderFlowSnapshot> recent = snapshotRepository
            .findBySymbolOrderByTimestampDesc(symbol,
                org.springframework.data.domain.PageRequest.of(0, 20))
            .getContent();

        if (recent.size() < 5) {
            return false;
        }

        // Calculate moving average
        double avgBuyerScore = recent.stream()
            .mapToInt(OrderFlowSnapshot::getBuyerPressureScore)
            .average()
            .orElse(50);

        // If current is > 2 standard deviations from mean, it's anomaly
        double stdDev = Math.sqrt(
            recent.stream()
                .mapToDouble(s -> Math.pow(s.getBuyerPressureScore() - avgBuyerScore, 2))
                .average()
                .orElse(0)
        );

        double zScore = Math.abs(current.getBuyerPressureScore() - avgBuyerScore) / (stdDev + 1);

        return zScore > 2.0;  // 2 std devs = anomaly
    }
}
