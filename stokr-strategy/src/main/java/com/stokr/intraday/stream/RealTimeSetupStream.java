package com.stokr.intraday.stream;

import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.engine.MarketRegimeDetector;
import com.stokr.intraday.service.SetupDetectionService;
import com.stokr.intraday.service.SetupDetectionService.MarketData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Real-time stream processor for continuous setup detection
 *
 * Features:
 * - Processes market ticks as they arrive
 * - Updates market regime every minute
 * - Detects setups with <100ms latency
 * - Maintains ranking board (top 12 setups)
 * - Publishes events to subscribers
 */
@Service
@Slf4j
public class RealTimeSetupStream {

    private final SetupDetectionService detectionService;
    private final MarketRegimeDetector regimeDetector;
    private final List<SetupStreamListener> listeners = new CopyOnWriteArrayList<>();

    private volatile MarketRegimeDetector.MarketRegime currentRegime = MarketRegimeDetector.MarketRegime.CHOPPY;
    private volatile Instant lastRegimeUpdate = Instant.now();
    private volatile List<CurrentSetup> rankingBoard = new ArrayList<>();

    // Market state tracking
    private final Map<String, MarketTickData> latestTicks = new java.util.concurrent.ConcurrentHashMap<>();

    public RealTimeSetupStream(
            SetupDetectionService detectionService,
            MarketRegimeDetector regimeDetector) {
        this.detectionService = detectionService;
        this.regimeDetector = regimeDetector;
    }

    /**
     * Process incoming market tick for one stock
     * Called every 1-5 seconds per stock during market hours
     *
     * @param stockId NSE stock symbol
     * @param currentPrice Current market price
     * @param currentVwap Current VWAP
     * @param openPrice Day's open
     * @param volume Current volume
     * @param atr14 14-period ATR
     * @param nifty50 Current NIFTY 50 price (for regime detection)
     */
    public void processTick(String stockId, BigDecimal currentPrice, BigDecimal currentVwap,
                           BigDecimal openPrice, Long volume, BigDecimal atr14,
                           BigDecimal nifty50) {

        long startTime = System.currentTimeMillis();

        // Update market regime every 60 seconds
        if (shouldUpdateRegime()) {
            updateMarketRegime(nifty50);
        }

        // Store tick for reference
        MarketTickData tick = new MarketTickData();
        tick.stockId = stockId;
        tick.price = currentPrice;
        tick.vwap = currentVwap;
        tick.open = openPrice;
        tick.volume = volume;
        tick.atr14 = atr14;
        tick.timestamp = Instant.now();
        latestTicks.put(stockId, tick);

        // Publish tick event to subscribers
        publishEvent(new SetupStreamEvent.TickReceived(stockId, currentPrice, Instant.now()));

        // Log latency
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > 100) {
            log.warn("stream.high_latency stock={} latency_ms={}", stockId, elapsed);
        }
    }

    /**
     * Update market regime based on NIFTY 50 and derived metrics
     * Called every 60 seconds
     */
    private synchronized void updateMarketRegime(BigDecimal nifty50) {
        try {
            // Calculate NIFTY 50 change, momentum, volatility, volume
            // In production, would pull from real market data
            BigDecimal nifty50Change = BigDecimal.valueOf(0.01); // Placeholder
            BigDecimal momentum = BigDecimal.valueOf(0.5);      // Placeholder
            BigDecimal volatility = BigDecimal.valueOf(0.015);  // Placeholder
            BigDecimal volumeRatio = BigDecimal.valueOf(1.1);   // Placeholder

            MarketRegimeDetector.RegimeSnapshot snapshot = regimeDetector.detectRegime(
                    nifty50, nifty50Change, momentum, volatility, volumeRatio
            );

            MarketRegimeDetector.MarketRegime newRegime = snapshot.regime;
            if (!newRegime.equals(currentRegime)) {
                log.info("stream.regime_change from={} to={}", currentRegime, newRegime);
                currentRegime = newRegime;
                publishEvent(new SetupStreamEvent.RegimeChanged(newRegime, Instant.now()));
            }

            lastRegimeUpdate = Instant.now();
        } catch (Exception e) {
            log.error("stream.regime_update_error", e);
        }
    }

    /**
     * Check if regime should be updated (every 60 seconds)
     */
    private boolean shouldUpdateRegime() {
        return java.time.temporal.ChronoUnit.SECONDS.between(lastRegimeUpdate, Instant.now()) >= 60;
    }

    /**
     * Update ranking board with new setups
     * Called periodically (every 5 minutes or on significant change)
     */
    public synchronized void updateRankingBoard(List<CurrentSetup> newSetups) {
        try {
            // Merge with existing board, keeping most recent
            Map<String, CurrentSetup> board = new java.util.LinkedHashMap<>();

            // Add existing setups that haven't expired
            rankingBoard.stream()
                    .filter(setup -> setup.getExpiresAt() != null &&
                            setup.getExpiresAt().isAfter(Instant.now()))
                    .forEach(setup -> board.put(setup.getStockId() + ":" + setup.getSetupType(), setup));

            // Add/update with new setups
            newSetups.forEach(setup -> board.put(setup.getStockId() + ":" + setup.getSetupType(), setup));

            // Sort by quality score and keep top 12
            rankingBoard = board.values().stream()
                    .sorted(Comparator.comparing(CurrentSetup::getQualityScore).reversed())
                    .limit(12)
                    .toList();

            log.info("stream.ranking_board updated count={} top_score={}",
                    rankingBoard.size(),
                    rankingBoard.isEmpty() ? 0 : rankingBoard.get(0).getQualityScore()
            );

            // Publish board update
            publishEvent(new SetupStreamEvent.RankingBoardUpdated(rankingBoard, Instant.now()));
        } catch (Exception e) {
            log.error("stream.ranking_update_error", e);
        }
    }

    /**
     * Get current ranking board (top 12 setups)
     */
    public List<CurrentSetup> getRankingBoard() {
        return new ArrayList<>(rankingBoard);
    }

    /**
     * Get current market regime
     */
    public MarketRegimeDetector.MarketRegime getCurrentRegime() {
        return currentRegime;
    }

    /**
     * Get latest tick for a stock
     */
    public MarketTickData getLatestTick(String stockId) {
        return latestTicks.get(stockId);
    }

    /**
     * Register listener for stream events
     */
    public void subscribe(SetupStreamListener listener) {
        listeners.add(listener);
        log.debug("stream.listener_registered count={}", listeners.size());
    }

    /**
     * Unregister listener
     */
    public void unsubscribe(SetupStreamListener listener) {
        listeners.remove(listener);
        log.debug("stream.listener_unregistered count={}", listeners.size());
    }

    /**
     * Publish event to all subscribers
     */
    private void publishEvent(SetupStreamEvent event) {
        listeners.forEach(listener -> {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.error("stream.listener_error", e);
            }
        });
    }

    /**
     * Get statistics about stream performance
     */
    public StreamStatistics getStatistics() {
        StreamStatistics stats = new StreamStatistics();
        stats.regime = currentRegime;
        stats.tickCount = latestTicks.size();
        stats.rankingBoardSize = rankingBoard.size();
        stats.listenerCount = listeners.size();
        stats.topSetupScore = rankingBoard.isEmpty() ? BigDecimal.ZERO : rankingBoard.get(0).getQualityScore();
        stats.timestamp = Instant.now();
        return stats;
    }

    /**
     * Market tick data DTO
     */
    public static class MarketTickData {
        public String stockId;
        public BigDecimal price;
        public BigDecimal vwap;
        public BigDecimal open;
        public Long volume;
        public BigDecimal atr14;
        public Instant timestamp;
    }

    /**
     * Stream statistics snapshot
     */
    public static class StreamStatistics {
        public MarketRegimeDetector.MarketRegime regime;
        public int tickCount;
        public int rankingBoardSize;
        public int listenerCount;
        public BigDecimal topSetupScore;
        public Instant timestamp;
    }

    /**
     * Listener interface for stream events
     */
    public interface SetupStreamListener {
        void onEvent(SetupStreamEvent event);
    }

    /**
     * Stream events
     */
    public static abstract class SetupStreamEvent {
        public final Instant timestamp;

        protected SetupStreamEvent(Instant timestamp) {
            this.timestamp = timestamp;
        }

        public static class TickReceived extends SetupStreamEvent {
            public final String stockId;
            public final BigDecimal price;

            public TickReceived(String stockId, BigDecimal price, Instant timestamp) {
                super(timestamp);
                this.stockId = stockId;
                this.price = price;
            }
        }

        public static class RegimeChanged extends SetupStreamEvent {
            public final MarketRegimeDetector.MarketRegime newRegime;

            public RegimeChanged(MarketRegimeDetector.MarketRegime newRegime, Instant timestamp) {
                super(timestamp);
                this.newRegime = newRegime;
            }
        }

        public static class RankingBoardUpdated extends SetupStreamEvent {
            public final List<CurrentSetup> setups;

            public RankingBoardUpdated(List<CurrentSetup> setups, Instant timestamp) {
                super(timestamp);
                this.setups = setups;
            }
        }

        public static class SetupDetected extends SetupStreamEvent {
            public final CurrentSetup setup;

            public SetupDetected(CurrentSetup setup, Instant timestamp) {
                super(timestamp);
                this.setup = setup;
            }
        }
    }
}
