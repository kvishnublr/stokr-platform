package com.stokr.intraday.metrics;

import com.stokr.intraday.metrics.domain.OrderFlowSnapshot;
import com.stokr.intraday.metrics.repository.OrderFlowSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderFlowCollectorService {

    private final OrderFlowSnapshotRepository snapshotRepository;
    private final OrderFlowValidationService validationService;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${stokr.orderflow.collection-enabled:false}")
    private boolean collectionEnabled;

    @Value("${stokr.orderflow.redis-cache-enabled:true}")
    private boolean redisCacheEnabled;

    // Track recent bid/ask ratio for trend detection
    private final Map<String, LinkedList<BigDecimal>> ratioHistory = new ConcurrentHashMap<>();
    private static final int RATIO_HISTORY_SIZE = 10;

    // Track recent buyer pressure for trend
    private final Map<String, LinkedList<Integer>> buyerPressureHistory = new ConcurrentHashMap<>();
    private static final int PRESSURE_HISTORY_SIZE = 10;

    /**
     * Process incoming NSE order book snapshot
     * Called from market data feed handler
     *
     * @param orderBook NSE order book data
     */
    public void processOrderBookSnapshot(NSEOrderBook orderBook) {
        if (!collectionEnabled) {
            return;
        }

        try {
            String symbol = orderBook.getSymbol();
            Instant now = Instant.now();

            // Parse order book data
            OrderFlowSnapshot snapshot = new OrderFlowSnapshot();
            snapshot.setSymbol(symbol);
            snapshot.setTimestamp(now);
            snapshot.setExchange("NSE");

            // Extract bid-ask data
            List<OrderLevel> bidLevels = orderBook.getBidLevels();
            List<OrderLevel> askLevels = orderBook.getAskLevels();

            if (bidLevels == null || bidLevels.isEmpty() || askLevels == null || askLevels.isEmpty()) {
                log.warn("orderflow.empty_levels symbol={}", symbol);
                return;
            }

            // Set bid prices and volumes
            snapshot.setBidPrice(bidLevels.get(0).getPrice());
            snapshot.setBidLevel1(bidLevels.size() > 0 ? bidLevels.get(0).getVolume() : null);
            snapshot.setBidLevel2(bidLevels.size() > 1 ? bidLevels.get(1).getVolume() : null);
            snapshot.setBidLevel3(bidLevels.size() > 2 ? bidLevels.get(2).getVolume() : null);
            snapshot.setBidLevel4(bidLevels.size() > 3 ? bidLevels.get(3).getVolume() : null);
            snapshot.setBidLevel5(bidLevels.size() > 4 ? bidLevels.get(4).getVolume() : null);

            // Aggregate bid volume
            long totalBidVolume = bidLevels.stream()
                .mapToLong(OrderLevel::getVolume)
                .sum();
            snapshot.setBidVolume(totalBidVolume);

            // Set ask prices and volumes
            snapshot.setAskPrice(askLevels.get(0).getPrice());
            snapshot.setAskLevel1(askLevels.size() > 0 ? askLevels.get(0).getVolume() : null);
            snapshot.setAskLevel2(askLevels.size() > 1 ? askLevels.get(1).getVolume() : null);
            snapshot.setAskLevel3(askLevels.size() > 2 ? askLevels.get(2).getVolume() : null);
            snapshot.setAskLevel4(askLevels.size() > 3 ? askLevels.get(3).getVolume() : null);
            snapshot.setAskLevel5(askLevels.size() > 4 ? askLevels.get(4).getVolume() : null);

            // Aggregate ask volume
            long totalAskVolume = askLevels.stream()
                .mapToLong(OrderLevel::getVolume)
                .sum();
            snapshot.setAskVolume(totalAskVolume);

            // Calculate metrics
            calculateMetrics(snapshot, bidLevels, askLevels);

            // Validate snapshot
            OrderFlowValidationService.ValidationResult validation =
                validationService.validateSnapshot(snapshot);

            if (!validation.isValid()) {
                log.debug("orderflow.validation_failed symbol={} errors={}",
                    symbol, validation.getErrors());
                snapshot.setIsValid(false);
                return;
            }

            snapshot.setIsValid(true);

            // Store in database asynchronously
            storeSnapshotAsync(snapshot);

            // Cache in Redis for real-time access
            if (redisCacheEnabled) {
                cacheInRedis(snapshot);
            }

            log.trace("orderflow.snapshot symbol={} bid/ask={} pressure={}",
                symbol, snapshot.getBidAskRatio(), snapshot.getBuyerPressureScore());

        } catch (Exception ex) {
            log.error("orderflow.process_failed {}", ex.getMessage(), ex);
        }
    }

    /**
     * Calculate all metrics from order book data
     */
    private void calculateMetrics(OrderFlowSnapshot snapshot,
                                 List<OrderLevel> bidLevels,
                                 List<OrderLevel> askLevels) {

        // Calculate spread
        BigDecimal spread = snapshot.getAskPrice()
            .subtract(snapshot.getBidPrice());
        snapshot.setSpread(spread);

        BigDecimal midPrice = snapshot.getMidPrice();
        BigDecimal spreadPct = spread
            .divide(midPrice, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        snapshot.setSpreadPct(spreadPct);

        // Calculate bid/ask ratio
        BigDecimal ratio = snapshot.getBidVolume() > 0
            ? BigDecimal.valueOf(snapshot.getBidVolume())
                .divide(BigDecimal.valueOf(snapshot.getAskVolume()), 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        snapshot.setBidAskRatio(ratio);

        // Calculate imbalance
        snapshot.setImbalance(snapshot.getBidVolume() - snapshot.getAskVolume());

        // Calculate cumulative depth (10 levels)
        long cumulativeBid = bidLevels.stream()
            .limit(10)
            .mapToLong(OrderLevel::getVolume)
            .sum();
        long cumulativeAsk = askLevels.stream()
            .limit(10)
            .mapToLong(OrderLevel::getVolume)
            .sum();
        snapshot.setCumulativeBid10Levels(cumulativeBid);
        snapshot.setCumulativeAsk10Levels(cumulativeAsk);

        // Calculate pressure scores
        snapshot.setBuyerPressureScore(calculateBuyerPressure(snapshot, bidLevels, askLevels));
        snapshot.setSellerPressureScore(calculateSellerPressure(snapshot, bidLevels, askLevels));

        // Calculate liquidity score
        snapshot.setLiquidityScore(calculateLiquidityScore(snapshot));

        // Determine pressure type
        snapshot.setPressureType(determinePressureType(snapshot));

        // Track trends
        trackRatioTrend(snapshot);
        trackPressureTrend(snapshot);

        // Store bid/ask initiated percentages
        calculateInitiatedPercentages(snapshot, bidLevels, askLevels);
    }

    /**
     * Calculate buyer pressure score (0-100)
     *
     * Factors:
     * - Bid/Ask ratio (40 points): How much more bid than ask
     * - Bid volume vs ASK volume (30 points): Absolute volume comparison
     * - Bid depth (20 points): How many levels have volume
     * - Trend (10 points): Is ratio increasing?
     */
    private int calculateBuyerPressure(OrderFlowSnapshot snapshot,
                                      List<OrderLevel> bidLevels,
                                      List<OrderLevel> askLevels) {
        int score = 0;

        // Factor 1: Bid/Ask Ratio (40 points max)
        BigDecimal ratio = snapshot.getBidAskRatio();
        if (ratio.compareTo(BigDecimal.valueOf(1.5)) > 0) {
            score += 40;  // Extreme buyer pressure
        } else if (ratio.compareTo(BigDecimal.valueOf(1.3)) > 0) {
            score += 32;
        } else if (ratio.compareTo(BigDecimal.valueOf(1.1)) > 0) {
            score += 24;
        } else if (ratio.compareTo(BigDecimal.ONE) > 0) {
            score += 12;
        } else if (ratio.compareTo(BigDecimal.ONE) < 0) {
            score += 0;  // Seller pressure
        } else {
            score += 6;  // Equal
        }

        // Factor 2: Bid vs Ask volume (30 points max)
        double volumeRatio = (double) snapshot.getBidVolume() / snapshot.getAskVolume();
        if (volumeRatio > 1.5) {
            score += 30;
        } else if (volumeRatio > 1.2) {
            score += 20;
        } else if (volumeRatio > 1.0) {
            score += 10;
        }

        // Factor 3: Bid depth - count levels with volume (20 points max)
        int bidLevelsWithVolume = (int) bidLevels.stream()
            .filter(l -> l.getVolume() > 0)
            .limit(5)
            .count();
        score += Math.min(20, bidLevelsWithVolume * 4);

        // Factor 4: Trend (10 points max)
        int ratioTrend = calculateRatioTrendScore(snapshot.getSymbol());
        score += ratioTrend;  // -10 to +10

        return Math.min(100, Math.max(0, score));
    }

    /**
     * Calculate seller pressure score (0-100)
     */
    private int calculateSellerPressure(OrderFlowSnapshot snapshot,
                                       List<OrderLevel> bidLevels,
                                       List<OrderLevel> askLevels) {
        // Seller pressure is inverse of buyer pressure
        int buyerScore = calculateBuyerPressure(snapshot, bidLevels, askLevels);

        // If buyer pressure is 70, seller pressure should be low
        // Use: sellerScore = 100 - buyerScore + some adjustment
        int baseSellerScore = 100 - buyerScore;

        // Adjust based on ask volume and depth
        BigDecimal ratio = snapshot.getBidAskRatio();
        int adjustment = 0;

        if (ratio.compareTo(BigDecimal.valueOf(0.7)) < 0) {
            adjustment += 30;  // Strong seller pressure
        } else if (ratio.compareTo(BigDecimal.valueOf(0.85)) < 0) {
            adjustment += 15;
        }

        // Ask depth bonus
        int askLevelsWithVolume = (int) askLevels.stream()
            .filter(l -> l.getVolume() > 0)
            .limit(5)
            .count();
        adjustment += Math.min(10, askLevelsWithVolume * 2);

        return Math.min(100, Math.max(0, baseSellerScore + adjustment - 25));
    }

    /**
     * Calculate liquidity score (0-100)
     *
     * Tight spread = high liquidity
     * Wide spread = low liquidity
     */
    private int calculateLiquidityScore(OrderFlowSnapshot snapshot) {
        int score = 50;  // Baseline

        BigDecimal spreadPct = snapshot.getSpreadPct();

        // Spread analysis (tighter = better)
        if (spreadPct.compareTo(BigDecimal.valueOf(0.02)) < 0) {
            score += 40;  // Ultra tight spread
        } else if (spreadPct.compareTo(BigDecimal.valueOf(0.05)) < 0) {
            score += 30;
        } else if (spreadPct.compareTo(BigDecimal.valueOf(0.10)) < 0) {
            score += 20;
        } else if (spreadPct.compareTo(BigDecimal.valueOf(0.20)) < 0) {
            score += 10;
        } else if (spreadPct.compareTo(BigDecimal.valueOf(0.50)) < 0) {
            score -= 5;
        } else {
            score -= 25;  // Wide spread, poor liquidity
        }

        // Volume balance bonus (stable markets have balanced bid/ask)
        BigDecimal ratio = snapshot.getBidAskRatio();
        if (ratio.compareTo(BigDecimal.valueOf(0.9)) > 0 &&
            ratio.compareTo(BigDecimal.valueOf(1.1)) < 0) {
            score += 10;  // Balanced = stable
        }

        // Depth bonus (more volume at multiple levels = higher liquidity)
        if (snapshot.getCumulativeBid10Levels() != null &&
            snapshot.getCumulativeAsk10Levels() != null) {
            long totalDepth = snapshot.getCumulativeBid10Levels() +
                             snapshot.getCumulativeAsk10Levels();
            if (totalDepth > 500_000) {
                score += 5;
            }
        }

        return Math.min(100, Math.max(0, score));
    }

    /**
     * Determine pressure type label
     */
    private String determinePressureType(OrderFlowSnapshot snapshot) {
        int buyerScore = snapshot.getBuyerPressureScore();
        int sellerScore = snapshot.getSellerPressureScore();
        int liquidityScore = snapshot.getLiquidityScore();

        if (liquidityScore < 40) {
            return "POOR_LIQUIDITY";
        }

        if (buyerScore > 70) {
            return "STRONG_BUY_PRESSURE";
        } else if (buyerScore > 55) {
            return "BUY_PRESSURE";
        } else if (sellerScore > 70) {
            return "STRONG_SELL_PRESSURE";
        } else if (sellerScore > 55) {
            return "SELL_PRESSURE";
        } else {
            return "BALANCED";
        }
    }

    /**
     * Track bid/ask ratio trend
     */
    private void trackRatioTrend(OrderFlowSnapshot snapshot) {
        LinkedList<BigDecimal> history = ratioHistory.computeIfAbsent(
            snapshot.getSymbol(),
            k -> new LinkedList<>()
        );

        history.addFirst(snapshot.getBidAskRatio());
        while (history.size() > RATIO_HISTORY_SIZE) {
            history.removeLast();
        }

        // Calculate momentum
        if (history.size() >= 3) {
            BigDecimal current = history.get(0);
            BigDecimal previous = history.get(1);
            BigDecimal momentum = current.subtract(previous)
                .multiply(BigDecimal.valueOf(100));
            snapshot.setBidAskMomentum(momentum.intValue());
        }
    }

    /**
     * Track buyer pressure trend
     */
    private void trackPressureTrend(OrderFlowSnapshot snapshot) {
        LinkedList<Integer> history = buyerPressureHistory.computeIfAbsent(
            snapshot.getSymbol(),
            k -> new LinkedList<>()
        );

        history.addFirst(snapshot.getBuyerPressureScore());
        while (history.size() > PRESSURE_HISTORY_SIZE) {
            history.removeLast();
        }
    }

    /**
     * Calculate ratio trend score (-10 to +10)
     * Positive = ratio increasing (more buying)
     * Negative = ratio decreasing (more selling)
     */
    private int calculateRatioTrendScore(String symbol) {
        LinkedList<BigDecimal> history = ratioHistory.get(symbol);
        if (history == null || history.size() < 2) {
            return 0;
        }

        BigDecimal current = history.get(0);
        BigDecimal previous = history.get(1);

        BigDecimal change = current.subtract(previous);

        if (change.compareTo(BigDecimal.valueOf(0.1)) > 0) {
            return 10;  // Strong increase
        } else if (change.compareTo(BigDecimal.valueOf(0.05)) > 0) {
            return 5;
        } else if (change.compareTo(BigDecimal.valueOf(-0.1)) < 0) {
            return -10;  // Strong decrease
        } else if (change.compareTo(BigDecimal.valueOf(-0.05)) < 0) {
            return -5;
        }

        return 0;
    }

    /**
     * Calculate buyer vs seller initiated trade percentages
     */
    private void calculateInitiatedPercentages(OrderFlowSnapshot snapshot,
                                              List<OrderLevel> bidLevels,
                                              List<OrderLevel> askLevels) {
        // Buyer initiated = aggressive buys (hitting ask)
        // Seller initiated = aggressive sells (hitting bid)

        long buyerInitiated = snapshot.getAskVolume();  // Approximate
        long sellerInitiated = snapshot.getBidVolume();

        long total = buyerInitiated + sellerInitiated;

        if (total > 0) {
            BigDecimal buyerPct = BigDecimal.valueOf(buyerInitiated)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
            snapshot.setBuyerInitiatedPct(buyerPct);

            BigDecimal sellerPct = BigDecimal.valueOf(sellerInitiated)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
            snapshot.setSellerInitiatedPct(sellerPct);
        }
    }

    /**
     * Store snapshot in database asynchronously
     */
    @Async
    private void storeSnapshotAsync(OrderFlowSnapshot snapshot) {
        try {
            snapshotRepository.save(snapshot);
            log.trace("orderflow.stored symbol={} id={}", snapshot.getSymbol(), snapshot.getId());
        } catch (Exception ex) {
            log.error("orderflow.storage_failed symbol={} {}", snapshot.getSymbol(), ex.getMessage());
        }
    }

    /**
     * Cache snapshot in Redis for real-time access
     */
    private void cacheInRedis(OrderFlowSnapshot snapshot) {
        try {
            String key = "orderflow:" + snapshot.getSymbol();
            String json = serializeSnapshot(snapshot);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(2));
        } catch (Exception ex) {
            log.warn("orderflow.redis_cache_failed symbol={}", snapshot.getSymbol());
        }
    }

    /**
     * Serialize snapshot to JSON for Redis
     */
    private String serializeSnapshot(OrderFlowSnapshot snapshot) {
        // In production, use ObjectMapper
        return String.format(
            "{\"symbol\":\"%s\",\"ratio\":%s,\"buyPressure\":%d,\"liquidity\":%d}",
            snapshot.getSymbol(),
            snapshot.getBidAskRatio(),
            snapshot.getBuyerPressureScore(),
            snapshot.getLiquidityScore()
        );
    }

    /**
     * Get current order flow state from Redis (fast path)
     */
    public OrderFlowSnapshot getLatestSnapshot(String symbol) {
        // Try Redis first
        try {
            String cached = (String) redisTemplate.opsForValue()
                .get("orderflow:" + symbol);
            if (cached != null) {
                return snapshotRepository.findFirstBySymbolAndIsValidTrueOrderByTimestampDesc(symbol).orElse(null);
            }
        } catch (Exception ex) {
            log.trace("orderflow.redis_miss symbol={}", symbol);
        }

        // Fallback to DB
        return snapshotRepository.findFirstBySymbolAndIsValidTrueOrderByTimestampDesc(symbol).orElse(null);
    }

    /**
     * DTO for NSE order book (input to processOrderBookSnapshot)
     */
    public static class NSEOrderBook {
        private String symbol;
        private List<OrderLevel> bidLevels;
        private List<OrderLevel> askLevels;

        public NSEOrderBook(String symbol, List<OrderLevel> bidLevels, List<OrderLevel> askLevels) {
            this.symbol = symbol;
            this.bidLevels = bidLevels;
            this.askLevels = askLevels;
        }

        public String getSymbol() { return symbol; }
        public List<OrderLevel> getBidLevels() { return bidLevels; }
        public List<OrderLevel> getAskLevels() { return askLevels; }
    }

    /**
     * DTO for order level (price + volume)
     */
    public static class OrderLevel {
        private BigDecimal price;
        private long volume;

        public OrderLevel(BigDecimal price, long volume) {
            this.price = price;
            this.volume = volume;
        }

        public BigDecimal getPrice() { return price; }
        public long getVolume() { return volume; }
    }
}
