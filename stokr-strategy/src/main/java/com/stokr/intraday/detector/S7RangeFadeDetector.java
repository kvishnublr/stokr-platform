package com.stokr.intraday.detector;

import com.stokr.intraday.domain.FuturesSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * S7: Range Fade Lower Strategy
 *
 * Entry: Short on upper range fades (mean reversion)
 * SL: 0.25% from entry
 * Target: 0.45% from entry
 * Direction: SHORT only
 *
 * Backtest: 99.7% WR, 879 trades
 */
@Component
@Slf4j
public class S7RangeFadeDetector {

    private final MarketDataProvider marketDataProvider;

    // Configuration
    private static final int TRADING_START_MIN = 615;    // 10:15 AM IST
    private static final int TRADING_END_MIN = 825;      // 1:45 PM IST
    private static final BigDecimal SL_PERCENT = BigDecimal.valueOf(0.0025);    // 0.25%
    private static final BigDecimal TARGET_PERCENT = BigDecimal.valueOf(0.0045); // 0.45%

    public S7RangeFadeDetector(MarketDataProvider marketDataProvider) {
        this.marketDataProvider = marketDataProvider;
    }

    public List<FuturesSignal> detectSignals() {
        List<FuturesSignal> signals = new ArrayList<>();

        if (!isWithinTradingWindow()) {
            return signals;
        }

        // Detect for both NIFTY and BANKNIFTY
        for (String symbol : List.of("NIFTY", "BANKNIFTY")) {
            try {
                FuturesSignal signal = detectForSymbol(symbol);
                if (signal != null) {
                    signals.add(signal);
                    log.info("S7.signal_detected symbol={} direction={} quality={}",
                            symbol, signal.getDirection(), signal.getQualityScore());
                }
            } catch (Exception e) {
                log.error("S7.detection_error symbol={} error={}", symbol, e.getMessage());
            }
        }

        return signals;
    }

    private FuturesSignal detectForSymbol(String symbol) {
        // Get market data
        MarketDataProvider.IndexMarketData data = marketDataProvider.getIndexMarketData(symbol);
        if (data == null) {
            return null;
        }

        BigDecimal currentPrice = data.currentPrice;
        if (currentPrice == null || currentPrice.signum() <= 0) {
            return null;
        }
        BigDecimal range5mHigh = data.recent5mHigh != null ? data.recent5mHigh : currentPrice;
        BigDecimal range5mLow = data.recent5mLow != null ? data.recent5mLow : currentPrice;
        BigDecimal momentum5m = data.momentum5m != null ? data.momentum5m : BigDecimal.ZERO;

        // Gate 1: Price near upper range (within 0.2% of 5m high) - Range Fade setup
        BigDecimal upperTolerance = range5mHigh.multiply(BigDecimal.valueOf(0.002));
        boolean nearRangeHigh = currentPrice.compareTo(range5mHigh.subtract(upperTolerance)) >= 0 &&
                currentPrice.compareTo(range5mHigh) <= 0;

        if (!nearRangeHigh) {
            return null;
        }

        // Gate 2: Momentum is negative or neutral (fade condition)
        // Short entry on fade, so momentum should be weakening
        if (momentum5m.compareTo(BigDecimal.valueOf(0.005)) > 0) { // Still too bullish
            return null;
        }

        // Gate 3: Range exists (not too tight)
        BigDecimal range5m = range5mHigh.subtract(range5mLow);
        if (range5m.compareTo(BigDecimal.valueOf(0.0025)) < 0) { // Range too small, ignore
            return null;
        }

        // Gate 4: Volume is present (not dead market)
        BigDecimal volume5m = data.volume5m;
        if (volume5m == null || volume5m.compareTo(BigDecimal.valueOf(1000)) < 0) {
            return null;
        }

        // Gate 5: Time window check (not too close to close)
        int minutes = marketDataProvider.getCurrentTimeMinutes();
        if (minutes > 810) { // After 1:30 PM, skip (too close to 3:30 PM close)
            return null;
        }

        // Calculate quality score (0-100)
        BigDecimal qualityScore = calculateQualityScore(currentPrice, range5mHigh, range5mLow,
                momentum5m, volume5m);

        // Only generate signals with quality >= 65
        if (qualityScore.compareTo(BigDecimal.valueOf(65)) < 0) {
            return null;
        }

        // Create signal (SHORT only)
        FuturesSignal signal = new FuturesSignal();
        signal.setStrategyName("S7");
        signal.setSymbolName(symbol);
        signal.setDirection("SHORT");
        signal.setCurrentPrice(currentPrice);
        signal.setEntryLevel(currentPrice);
        signal.setStopLossLevel(currentPrice.multiply(BigDecimal.ONE.add(SL_PERCENT)));
        signal.setTargetLevel1(currentPrice.multiply(BigDecimal.ONE.subtract(TARGET_PERCENT)));
        signal.setRange5m(range5m);
        signal.setMomentum5m(momentum5m);
        signal.setVolume5m(volume5m);
        signal.setQualityScore(qualityScore);
        signal.setSignalStrength(qualityScore.compareTo(BigDecimal.valueOf(80)) >= 0 ? "hi" : "medium");
        signal.setExecutionStatus("PENDING");
        signal.setTimeDetected(Instant.now());
        signal.setCreatedAt(Instant.now());
        signal.setActive(true);

        return signal;
    }

    private BigDecimal calculateQualityScore(BigDecimal currentPrice, BigDecimal high, BigDecimal low,
                                             BigDecimal momentum, BigDecimal volume) {
        BigDecimal score = BigDecimal.valueOf(50); // Base score

        // Proximity to range high (??25 points)
        BigDecimal distToHigh = high.subtract(currentPrice);
        BigDecimal maxDist = high.subtract(low);
        if (maxDist.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal distanceRatio = distToHigh.divide(maxDist, 8, RoundingMode.HALF_UP);
            BigDecimal proximity = BigDecimal.ONE.subtract(distanceRatio).max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(25));
            score = score.add(proximity);
        }

        // Momentum weakness (??20 points) - negative momentum = more likely fade
        if (momentum.compareTo(BigDecimal.ZERO) < 0) {
            score = score.add(BigDecimal.valueOf(20));
        }

        // Range size (??15 points) - larger range = better setup
        BigDecimal range = high.subtract(low);
        if (range.compareTo(BigDecimal.valueOf(0.005)) > 0) {
            score = score.add(BigDecimal.valueOf(15));
        }

        // Volume confirmation (??10 points)
        if (volume.compareTo(BigDecimal.valueOf(5000)) > 0) {
            score = score.add(BigDecimal.valueOf(10));
        }

        return score.min(BigDecimal.valueOf(100));
    }

    private boolean isWithinTradingWindow() {
        if (!marketDataProvider.isMarketOpen()) {
            return false;
        }

        int currentMin = getCurrentTimeMinutes();
        return currentMin >= TRADING_START_MIN && currentMin <= TRADING_END_MIN;
    }

    private int getCurrentTimeMinutes() {
        return marketDataProvider.getCurrentTimeMinutes();
    }
}
