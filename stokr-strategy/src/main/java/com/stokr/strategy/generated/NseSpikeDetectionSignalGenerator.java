package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureAnalysis;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureSnapshot;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.service.StrategyCandleLoader;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NSE_SPIKE_DETECTION V3.0 — PRESSURE-BASED INSTITUTIONAL STRATEGY
 *
 * CORE PHILOSOPHY:
 *   Entries are driven by ORDER BOOK IMBALANCE (real buy/sell pressure from NSE),
 *   NOT by lagging price patterns. When institutions pile into one side of the book
 *   and price confirms with multi-bar momentum + rising volume, that's a real signal.
 *
 * DATA SOURCES (all real-time, zero lag):
 *   1. Order Book: totalBuyQty / totalSellQty from Kite quote-mode WebSocket
 *   2. Price Action: 1m OHLCV candles from CandleAggregator
 *   3. Volume: Real share volume from Kite (not tick counts)
 *
 * ENTRY MODEL — 4 Components (weighted composite):
 *   ① ORDER BOOK IMBALANCE (35%) — buyQty/(buyQty+sellQty) ratio
 *   ② MULTI-BAR MOMENTUM   (25%) — 3 consecutive bars in same direction
 *   ③ VOLUME ACCELERATION   (25%) — volume increasing over last 3 bars
 *   ④ BAR QUALITY           (15%) — strong close, no rejection wicks
 *
 * FIRE IF: composite ≥ 75 AND imbalance ≥ 50 AND momentum ≥ 30
 *
 * DYNAMIC STOP: 3-bar swing low/high + buffer (structural support/resistance)
 * TARGET: 2.0× risk from entry (minimum 1.5 R:R)
 *
 * EXPECTED: 15-30 high-conviction signals per day
 */
@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "NSE_SPIKE_DETECTION",
    assetClass   = "EQUITY",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class NseSpikeDetectionSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "1m";
    private static final int BARS_FETCH = 30;
    private static final int MOMENTUM_BARS = 3;       // Multi-bar momentum window
    private static final int VOLUME_AVG_PERIOD = 15;   // Bars for volume average
    private static final int SWING_SL_BARS = 3;        // Bars for swing stop loss

    private final StrategyCandleLoader candleLoader;
    private final OrderBookPressureTracker pressureTracker;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    // ═══════════════════════════════════════════════════════════════════════════
    // PRESSURE PARAMETERS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Order book imbalance threshold for buy pressure (buyQty / total > this) */
    @Value("${stokr.strategy.spike.buy-pressure-threshold:0.58}")
    private double buyPressureThreshold;

    /** Order book imbalance threshold for sell pressure (buyQty / total < this) */
    @Value("${stokr.strategy.spike.sell-pressure-threshold:0.42}")
    private double sellPressureThreshold;

    /** Minimum pressure consistency: fraction of recent ticks with consistent direction */
    @Value("${stokr.strategy.spike.min-pressure-consistency:0.55}")
    private double minPressureConsistency;

    /** Lookback ticks for pressure analysis (at ~1 tick/sec, 60 = ~1 min) */
    @Value("${stokr.strategy.spike.pressure-lookback-ticks:90}")
    private int pressureLookbackTicks;

    // ═══════════════════════════════════════════════════════════════════════════
    // MOMENTUM + VOLUME PARAMETERS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Minimum cumulative % move over 3 bars to confirm momentum */
    @Value("${stokr.strategy.spike.min-momentum-pct:0.10}")
    private double minMomentumPct;

    /** Minimum volume multiple vs average for current bar */
    @Value("${stokr.strategy.spike.min-volume-multiple:1.2}")
    private double minVolumeMultiple;

    /** Maximum wick % before rejecting bar quality */
    @Value("${stokr.strategy.spike.max-wick-pct:0.60}")
    private double maxWickPct;

    // ═══════════════════════════════════════════════════════════════════════════
    // COMPOSITE + RISK PARAMETERS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Minimum composite score to fire (weighted average of 4 components) */
    @Value("${stokr.strategy.spike.min-composite-score:72.0}")
    private double minCompositeScore;

    /** Risk-reward multiplier for target calculation */
    @Value("${stokr.strategy.spike.target-rr-multiple:2.0}")
    private double targetRrMultiple;

    /** Minimum R:R to emit signal */
    @Value("${stokr.strategy.spike.min-risk-reward:1.5}")
    private double minRiskReward;

    /** SL buffer beyond swing point (%) */
    @Value("${stokr.strategy.spike.sl-buffer-pct:0.15}")
    private double slBufferPct;

    /** Cooldown seconds between signals per symbol */
    @Value("${stokr.strategy.spike.cooldown-seconds:600}")
    private int cooldownSeconds;

    @Override
    public String key() {
        return "NSE_SPIKE_DETECTION";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();

        // ─────────────────────────────────────────────────────────────────────
        // 1. SESSION GATE: 09:20-15:15 IST (skip first 5 min noise + last 15 min)
        // ─────────────────────────────────────────────────────────────────────
        if (context.asOf() != null) {
            LocalTime lt = context.asOf().atZone(zone).toLocalTime();
            if (lt.isBefore(LocalTime.of(9, 20)) || lt.isAfter(LocalTime.of(15, 15))) {
                return hold(context);
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // 2. LOAD CANDLE DATA
        // ─────────────────────────────────────────────────────────────────────
        List<MarketdataCandle> bars = candleLoader.bars(context, TIMEFRAME, BARS_FETCH);
        int n = bars.size();
        if (n < MOMENTUM_BARS + VOLUME_AVG_PERIOD) {
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 3. COMPONENT ①: ORDER BOOK IMBALANCE (weight: 35%)
        //    Primary signal — real institutional buy/sell pressure
        // ─────────────────────────────────────────────────────────────────────
        PressureSnapshot snapshot = pressureTracker.getSnapshot(symbol);
        PressureAnalysis pressureAnalysis = pressureTracker.analyze(symbol, pressureLookbackTicks);

        double imbalanceScore;
        boolean pressureBuy;

        if (snapshot == null || pressureAnalysis == null) {
            // No pressure data yet — use price-only fallback (reduced score)
            imbalanceScore = 0;
            pressureBuy = toDouble(bars.get(n - 1).getClosePrice()) > toDouble(bars.get(n - 2).getClosePrice());
        } else {
            double ratio = snapshot.imbalanceRatio();
            pressureBuy = ratio > 0.50;

            if (pressureBuy) {
                // Buy pressure scoring
                if (ratio > 0.70) imbalanceScore = 100;
                else if (ratio > 0.65) imbalanceScore = 85;
                else if (ratio > 0.60) imbalanceScore = 70;
                else if (ratio > buyPressureThreshold) imbalanceScore = 55;
                else imbalanceScore = 0;
            } else {
                // Sell pressure scoring (mirror)
                if (ratio < 0.30) imbalanceScore = 100;
                else if (ratio < 0.35) imbalanceScore = 85;
                else if (ratio < 0.40) imbalanceScore = 70;
                else if (ratio < sellPressureThreshold) imbalanceScore = 55;
                else imbalanceScore = 0;
            }

            // Penalize if pressure is inconsistent over time
            if (pressureAnalysis.pressureConsistency() < minPressureConsistency) {
                imbalanceScore *= 0.5;  // Halve score if pressure is flickering
            }
        }

        // Hard gate: must have at least some book pressure signal
        if (imbalanceScore < 40) {
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 4. COMPONENT ②: MULTI-BAR MOMENTUM (weight: 25%)
        //    3 consecutive bars must confirm the pressure direction
        // ─────────────────────────────────────────────────────────────────────
        double momentumScore = calculateMomentumScore(bars, n, pressureBuy);

        // Hard gate: need at least weak momentum alignment
        if (momentumScore < 30) {
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 5. COMPONENT ③: VOLUME ACCELERATION (weight: 25%)
        //    Volume should be rising — institutions executing, not fading
        // ─────────────────────────────────────────────────────────────────────
        double volumeAccelScore = calculateVolumeAccelerationScore(bars, n);

        // ─────────────────────────────────────────────────────────────────────
        // 6. COMPONENT ④: BAR QUALITY (weight: 15%)
        //    Current bar should have strong close in pressure direction
        // ─────────────────────────────────────────────────────────────────────
        double barQualityScore = calculateBarQualityScore(bars.get(n - 1), pressureBuy);

        // ─────────────────────────────────────────────────────────────────────
        // 7. WEIGHTED COMPOSITE SCORE
        // ─────────────────────────────────────────────────────────────────────
        double compositeScore = (imbalanceScore * 0.35)
                              + (momentumScore * 0.25)
                              + (volumeAccelScore * 0.25)
                              + (barQualityScore * 0.15);

        if (compositeScore < minCompositeScore) {
            log.debug("spike.low_composite symbol={} score={:.1f} [imb={:.0f} mom={:.0f} vol={:.0f} bar={:.0f}]",
                    symbol, compositeScore, imbalanceScore, momentumScore, volumeAccelScore, barQualityScore);
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 8. PRESSURE + PRICE AGREEMENT CHECK
        //    Book pressure and price must agree — no divergence
        // ─────────────────────────────────────────────────────────────────────
        double curClose = toDouble(bars.get(n - 1).getClosePrice());
        double prevClose = toDouble(bars.get(n - 2).getClosePrice());
        boolean priceUp = curClose > prevClose;

        if (pressureBuy != priceUp) {
            // Pressure says buy but price is falling (or vice versa) — divergence, skip
            log.debug("spike.pressure_price_divergence symbol={} pressureBuy={} priceUp={}", symbol, pressureBuy, priceUp);
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 9. COOLDOWN CHECK
        // ─────────────────────────────────────────────────────────────────────
        Instant now = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null && Duration.between(lastEmit, now).getSeconds() < cooldownSeconds) {
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 10. DYNAMIC STOP LOSS — 3-bar swing structure
        // ─────────────────────────────────────────────────────────────────────
        double entryPrice = curClose;
        double stopLoss;

        if (pressureBuy) {
            // BUY: SL = lowest low of last 3 bars - buffer
            double swingLow = Double.MAX_VALUE;
            for (int i = n - SWING_SL_BARS; i < n; i++) {
                double low = toDouble(bars.get(i).getLowPrice());
                if (low > 0 && low < swingLow) swingLow = low;
            }
            stopLoss = swingLow * (1.0 - slBufferPct / 100.0);
        } else {
            // SELL: SL = highest high of last 3 bars + buffer
            double swingHigh = Double.MIN_VALUE;
            for (int i = n - SWING_SL_BARS; i < n; i++) {
                double high = toDouble(bars.get(i).getHighPrice());
                if (high > swingHigh) swingHigh = high;
            }
            stopLoss = swingHigh * (1.0 + slBufferPct / 100.0);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 11. TARGET — R:R based
        // ─────────────────────────────────────────────────────────────────────
        double risk = Math.abs(entryPrice - stopLoss);
        double target;
        if (pressureBuy) {
            target = entryPrice + (risk * targetRrMultiple);
        } else {
            target = entryPrice - (risk * targetRrMultiple);
        }

        double rr = risk > 0 ? Math.abs(target - entryPrice) / risk : 0;
        if (rr < minRiskReward) {
            return hold(context);
        }

        // Mark cooldown
        lastEmitBySymbol.put(symbol, now);

        // ─────────────────────────────────────────────────────────────────────
        // 12. SIGNAL
        // ─────────────────────────────────────────────────────────────────────
        SignalType signalType = pressureBuy ? SignalType.BUY : SignalType.SELL;

        // Pressure details for logging
        String pressureInfo = snapshot != null
                ? String.format("imb=%.0f%%", snapshot.imbalanceRatio() * 100)
                : "imb=N/A";
        String consistencyInfo = pressureAnalysis != null
                ? String.format("consist=%.0f%%", pressureAnalysis.pressureConsistency() * 100)
                : "";

        double volMultiple = calculateCurrentVolumeMultiple(bars, n);
        double momPct = calculateCumulativeMomentum(bars, n, MOMENTUM_BARS);

        String reason = String.format(
            "NSE_SPIKE_V3 %s: %s %s mom=%.2f%% vol=%.1fx composite=%.1f " +
            "[imb=%.0f mom=%.0f vol=%.0f bar=%.0f] entry=%.2f sl=%.2f target=%.2f rr=%.1f",
            signalType, pressureInfo, consistencyInfo, momPct, volMultiple, compositeScore,
            imbalanceScore, momentumScore, volumeAccelScore, barQualityScore,
            entryPrice, stopLoss, target, rr
        );

        log.info("spike_v3.signal symbol={} {}", symbol, reason);
        return new StrategySignal(
                signalType, symbol, BigDecimal.ONE, reason,
                BigDecimal.valueOf(entryPrice),
                BigDecimal.valueOf(stopLoss),
                BigDecimal.valueOf(target)
        );
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // SCORING METHODS
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * MULTI-BAR MOMENTUM: Checks last 3 bars for sustained directional movement.
     * Eliminates single-bar noise and whipsaws.
     */
    private double calculateMomentumScore(List<MarketdataCandle> bars, int n, boolean expectBuy) {
        if (n < MOMENTUM_BARS + 1) return 0;

        int alignedBars = 0;
        boolean allIncreasing = true;  // Each bar more extreme than previous
        double cumulativeMove = 0;

        for (int i = n - MOMENTUM_BARS; i < n; i++) {
            double close = toDouble(bars.get(i).getClosePrice());
            double open = toDouble(bars.get(i).getOpenPrice());
            double prevClose = toDouble(bars.get(i - 1).getClosePrice());

            if (expectBuy) {
                if (close > open) alignedBars++;  // Green bar
                if (i > n - MOMENTUM_BARS && close <= toDouble(bars.get(i - 1).getClosePrice())) {
                    allIncreasing = false;
                }
            } else {
                if (close < open) alignedBars++;  // Red bar
                if (i > n - MOMENTUM_BARS && close >= toDouble(bars.get(i - 1).getClosePrice())) {
                    allIncreasing = false;
                }
            }
            cumulativeMove += (close - prevClose) / prevClose * 100;
        }

        double absMomentum = Math.abs(cumulativeMove);

        // All 3 bars aligned + each making new extremes + strong move
        if (alignedBars == MOMENTUM_BARS && allIncreasing && absMomentum >= minMomentumPct * 2) return 100;
        // All 3 aligned + decent move
        if (alignedBars == MOMENTUM_BARS && absMomentum >= minMomentumPct) return 80;
        // 2 of 3 aligned + some move
        if (alignedBars >= 2 && absMomentum >= minMomentumPct) return 55;
        // 2 of 3 aligned
        if (alignedBars >= 2) return 35;
        // Weak
        return 0;
    }

    /**
     * VOLUME ACCELERATION: Volume should be INCREASING over last 3 bars.
     * Institutional moves accelerate — retail pops fade.
     */
    private double calculateVolumeAccelerationScore(List<MarketdataCandle> bars, int n) {
        if (n < MOMENTUM_BARS + VOLUME_AVG_PERIOD) return 50;

        // Average volume of older bars (baseline)
        double avgVol = 0;
        int count = 0;
        for (int i = n - MOMENTUM_BARS - VOLUME_AVG_PERIOD; i < n - MOMENTUM_BARS; i++) {
            if (i < 0) continue;
            double v = toDouble(bars.get(i).getVolume());
            if (v > 0) { avgVol += v; count++; }
        }
        avgVol = count > 0 ? avgVol / count : 0;
        if (avgVol <= 0) return 50;

        // Volume of last 3 bars
        double[] recentVol = new double[MOMENTUM_BARS];
        for (int i = 0; i < MOMENTUM_BARS; i++) {
            recentVol[i] = toDouble(bars.get(n - MOMENTUM_BARS + i).getVolume());
        }

        double currentMultiple = recentVol[MOMENTUM_BARS - 1] / avgVol;

        // Check if volume is accelerating (each bar higher than previous)
        boolean accelerating = true;
        for (int i = 1; i < MOMENTUM_BARS; i++) {
            if (recentVol[i] < recentVol[i - 1] * 0.9) { // Allow 10% tolerance
                accelerating = false;
                break;
            }
        }

        // Accelerating + high multiple
        if (accelerating && currentMultiple >= 3.0) return 100;
        if (accelerating && currentMultiple >= 2.0) return 85;
        if (accelerating && currentMultiple >= minVolumeMultiple) return 70;
        // High volume but not accelerating
        if (currentMultiple >= 2.0) return 60;
        if (currentMultiple >= minVolumeMultiple) return 45;
        // Below average
        if (currentMultiple >= 0.8) return 20;
        return 0;
    }

    /**
     * BAR QUALITY: Current bar should close strongly in the pressure direction.
     * Reject wicky/indecisive candles.
     */
    private double calculateBarQualityScore(MarketdataCandle bar, boolean isBuy) {
        double high = toDouble(bar.getHighPrice());
        double low = toDouble(bar.getLowPrice());
        double close = toDouble(bar.getClosePrice());
        double open = toDouble(bar.getOpenPrice());
        double range = high - low;

        if (range <= 0) return 40;

        double bodySize = Math.abs(close - open);
        double bodyRatio = bodySize / range;

        if (isBuy) {
            // BUY: want close near high, small upper wick
            double upperWick = high - Math.max(close, open);
            double wickRatio = range > 0 ? upperWick / range : 0;
            if (wickRatio > maxWickPct) return 0;  // Rejection candle

            if (close > open && bodyRatio > 0.60) return 100;  // Strong green bar
            if (close > open && bodyRatio > 0.40) return 70;
            if (close > open) return 50;  // Green but small body
            return 15;  // Red bar in buy setup — weak
        } else {
            // SELL: want close near low, small lower wick
            double lowerWick = Math.min(close, open) - low;
            double wickRatio = range > 0 ? lowerWick / range : 0;
            if (wickRatio > maxWickPct) return 0;  // Rejection candle

            if (close < open && bodyRatio > 0.60) return 100;  // Strong red bar
            if (close < open && bodyRatio > 0.40) return 70;
            if (close < open) return 50;
            return 15;  // Green bar in sell setup — weak
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═════════════════════════════════════════════════════════════════════════════

    private double calculateCurrentVolumeMultiple(List<MarketdataCandle> bars, int n) {
        double avgVol = 0;
        int count = 0;
        for (int i = Math.max(0, n - VOLUME_AVG_PERIOD - 1); i < n - 1; i++) {
            double v = toDouble(bars.get(i).getVolume());
            if (v > 0) { avgVol += v; count++; }
        }
        avgVol = count > 0 ? avgVol / count : 0;
        double curVol = toDouble(bars.get(n - 1).getVolume());
        return avgVol > 0 ? curVol / avgVol : 1.0;
    }

    private double calculateCumulativeMomentum(List<MarketdataCandle> bars, int n, int lookback) {
        if (n < lookback + 1) return 0;
        double startPrice = toDouble(bars.get(n - lookback - 1).getClosePrice());
        double endPrice = toDouble(bars.get(n - 1).getClosePrice());
        return startPrice > 0 ? (endPrice - startPrice) / startPrice * 100 : 0;
    }

    private static double toDouble(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }
}
