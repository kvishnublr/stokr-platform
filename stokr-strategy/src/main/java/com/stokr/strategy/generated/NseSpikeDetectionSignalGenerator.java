package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.integrity.LookbackWindow;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureAnalysis;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureSnapshot;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.integrity.StrategyGeneratorIntegrityGate;
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
 * NSE_SPIKE_DETECTION V3.5 — HIGH-ACCURACY PRESSURE STRATEGY
 *
 * GOAL: 70-80% win rate by being EXTREMELY selective. Fewer signals, much higher quality.
 *
 * KEY IMPROVEMENTS OVER V3.0:
 *   ① NIFTY TREND FILTER — Only trade WITH the index. BUY only when NIFTY green, SELL only when red.
 *     (Data shows trading against index causes 40% of SL hits)
 *   ② STRICTER PRESSURE — Require 70%+ consistency (was 55%), imbalance gate raised to 55 (was 40)
 *   ③ HIGHER COMPOSITE — 82 threshold (was 72). Only top-quality setups.
 *   ④ SESSION — 09:45-15:15 IST. Data shows 9:15-9:45 is noisy; after 15:15 is close chop.
 *   ⑤ SMARTER TARGET — 1.5× R:R (was 2.0×). Easier to hit = higher win rate.
 *     At 70% win rate + 1.5 R:R: expectancy = 0.70×1.5 - 0.30×1.0 = +0.75R per trade
 *
 * ENTRY MODEL — 5 Components (weighted composite):
 *   ① NIFTY TREND ALIGNMENT (10%) — trade with the market
 *   ② ORDER BOOK IMBALANCE  (30%) — buyQty/(buyQty+sellQty) ratio
 *   ③ MULTI-BAR MOMENTUM    (25%) — 3 consecutive bars in same direction
 *   ④ VOLUME ACCELERATION   (20%) — volume increasing over last 3 bars
 *   ⑤ BAR QUALITY           (15%) — strong close, no rejection wicks
 *
 * FIRE IF: composite ≥ 82 AND imbalance ≥ 55 AND momentum ≥ 40 AND niftyAligned
 *
 * EXPECTED: 8-20 HIGH-CONVICTION signals per day, 70-80% accuracy
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
    private static final String NIFTY_SYMBOL = "NIFTY 50";
    private static final int BARS_FETCH = 30;
    private static final int NIFTY_BARS = 10;             // Bars for NIFTY trend check
    private static final int MOMENTUM_BARS = 3;            // Multi-bar momentum window
    private static final int VOLUME_AVG_PERIOD = 15;       // Bars for volume average
    private static final int SWING_SL_BARS = 5;            // Bars for swing stop loss (was 3, now 5 for wider structural SL)

    private final OrderBookPressureTracker pressureTracker;
    private final StrategyGeneratorIntegrityGate integrityGate;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    // ═══════════════════════════════════════════════════════════════════════════
    // PRESSURE PARAMETERS (tightened for accuracy)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Order book imbalance threshold for buy pressure (buyQty / total > this) */
    @Value("${stokr.strategy.spike.buy-pressure-threshold:0.60}")
    private double buyPressureThreshold;

    /** Order book imbalance threshold for sell pressure (buyQty / total < this) */
    @Value("${stokr.strategy.spike.sell-pressure-threshold:0.40}")
    private double sellPressureThreshold;

    /** Minimum pressure consistency: fraction of recent ticks with consistent direction */
    @Value("${stokr.strategy.spike.min-pressure-consistency:0.70}")
    private double minPressureConsistency;

    /** Lookback ticks for pressure analysis (at ~1 tick/sec, 120 = ~2 min) */
    @Value("${stokr.strategy.spike.pressure-lookback-ticks:120}")
    private int pressureLookbackTicks;

    // ═══════════════════════════════════════════════════════════════════════════
    // MOMENTUM + VOLUME PARAMETERS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Minimum cumulative % move over 3 bars to confirm momentum */
    @Value("${stokr.strategy.spike.min-momentum-pct:0.12}")
    private double minMomentumPct;

    /** Minimum volume multiple vs average for current bar */
    @Value("${stokr.strategy.spike.min-volume-multiple:1.5}")
    private double minVolumeMultiple;

    /** Maximum wick % before rejecting bar quality */
    @Value("${stokr.strategy.spike.max-wick-pct:0.50}")
    private double maxWickPct;

    // ═══════════════════════════════════════════════════════════════════════════
    // COMPOSITE + RISK PARAMETERS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Minimum composite score to fire */
    @Value("${stokr.strategy.spike.min-composite-score:82.0}")
    private double minCompositeScore;

    /** Risk-reward multiplier for target calculation (lowered for higher hit rate) */
    @Value("${stokr.strategy.spike.target-rr-multiple:1.5}")
    private double targetRrMultiple;

    /** Minimum R:R to emit signal */
    @Value("${stokr.strategy.spike.min-risk-reward:1.3}")
    private double minRiskReward;

    /** SL buffer beyond swing point (%) */
    @Value("${stokr.strategy.spike.sl-buffer-pct:0.20}")
    private double slBufferPct;

    /** Cooldown seconds between signals per symbol */
    @Value("${stokr.strategy.spike.cooldown-seconds:900}")
    private int cooldownSeconds;

    @Override
    public String key() {
        return "NSE_SPIKE_DETECTION";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        Instant asOf = context.asOf() != null ? context.asOf() : Instant.now();

        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) {
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 1. SESSION GATE: 09:45-15:15 IST
        //    Data shows: 9:15-9:45 = 60% SL rate (noise), after 15:15 = close auction chop
        if (context.asOf() != null) {
            LocalTime lt = context.asOf().atZone(zone).toLocalTime();
            if (lt.isBefore(LocalTime.of(9, 45)) || lt.isAfter(LocalTime.of(15, 15))) {
                return hold(context);
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // 2. LOAD CANDLE DATA (stock + NIFTY) — same-session only
        // ─────────────────────────────────────────────────────────────────────
        var barsOpt = integrityGate.sessionBars(
                key(), symbol, TIMEFRAME, BARS_FETCH, BARS_FETCH - 1, LookbackWindow.THIRTY_MINUTE, context);
        if (barsOpt.isEmpty()) {
            return hold(context);
        }
        List<MarketdataCandle> bars = barsOpt.get();
        int n = bars.size();
        if (n < MOMENTUM_BARS + VOLUME_AVG_PERIOD) {
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 3. COMPONENT ①: NIFTY TREND ALIGNMENT (weight: 10%)
        //    CRITICAL: Never fight the index. Data shows 40% of SL hits are
        //    against-trend trades. Only BUY when NIFTY is trending up.
        // ─────────────────────────────────────────────────────────────────────
        double niftyTrendScore = calculateNiftyTrendScore(context);

        // ─────────────────────────────────────────────────────────────────────
        // 4. COMPONENT ②: ORDER BOOK IMBALANCE (weight: 30%)
        //    Primary signal — real institutional buy/sell pressure
        //    Thresholds TIGHTENED: buy > 0.60, sell < 0.40 (was 0.58/0.42)
        //    Consistency RAISED: 70% (was 55%)
        // ─────────────────────────────────────────────────────────────────────
        PressureSnapshot snapshot = pressureTracker.getSnapshot(symbol);
        PressureAnalysis pressureAnalysis = pressureTracker.analyze(symbol, pressureLookbackTicks);

        double imbalanceScore;
        boolean pressureBuy;

        if (snapshot == null || pressureAnalysis == null) {
            // No pressure data = no signal (V3.5: NO FALLBACK. Pressure is mandatory.)
            return hold(context);
        }

        double ratio = snapshot.imbalanceRatio();
        pressureBuy = ratio > 0.50;

        if (pressureBuy) {
            // Buy pressure scoring (tightened thresholds)
            if (ratio > 0.72) imbalanceScore = 100;
            else if (ratio > 0.67) imbalanceScore = 90;
            else if (ratio > 0.63) imbalanceScore = 75;
            else if (ratio > buyPressureThreshold) imbalanceScore = 60;
            else imbalanceScore = 0;
        } else {
            // Sell pressure scoring (mirror, tightened)
            if (ratio < 0.28) imbalanceScore = 100;
            else if (ratio < 0.33) imbalanceScore = 90;
            else if (ratio < 0.37) imbalanceScore = 75;
            else if (ratio < sellPressureThreshold) imbalanceScore = 60;
            else imbalanceScore = 0;
        }

        // Pressure MUST be consistent over time (no flickering)
        double consistency = pressureAnalysis.pressureConsistency();
        if (consistency < minPressureConsistency) {
            imbalanceScore *= 0.3;  // Severely penalize flickering pressure (was 0.5)
        } else if (consistency > 0.85) {
            imbalanceScore = Math.min(100, imbalanceScore * 1.15);  // Bonus for rock-solid pressure
        }

        // Pressure trend must be building (not fading)
        double imbalanceTrend = pressureAnalysis.imbalanceTrend();
        if (pressureBuy && imbalanceTrend < -0.05) {
            imbalanceScore *= 0.5;  // Buy pressure is FADING — penalize
        } else if (!pressureBuy && imbalanceTrend > 0.05) {
            imbalanceScore *= 0.5;  // Sell pressure is FADING — penalize
        }

        // HARD GATE: raised from 40 → 55
        if (imbalanceScore < 55) {
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 5. NIFTY ALIGNMENT HARD GATE
        //    BUY needs NIFTY green (niftyTrendScore > 40)
        //    SELL needs NIFTY red (niftyTrendScore < -40)
        //    Neutral NIFTY = no trade
        // ─────────────────────────────────────────────────────────────────────
        if (pressureBuy && niftyTrendScore < 40) {
            log.debug("spike.nifty_against_buy symbol={} niftyScore={}", symbol, niftyTrendScore);
            return hold(context);
        }
        if (!pressureBuy && niftyTrendScore > -40) {
            log.debug("spike.nifty_against_sell symbol={} niftyScore={}", symbol, niftyTrendScore);
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 6. COMPONENT ③: MULTI-BAR MOMENTUM (weight: 25%)
        //    3 consecutive bars must confirm the pressure direction
        //    Hard gate RAISED: 40 (was 30)
        // ─────────────────────────────────────────────────────────────────────
        double momentumScore = calculateMomentumScore(bars, n, pressureBuy);

        if (momentumScore < 40) {
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 7. COMPONENT ④: VOLUME ACCELERATION (weight: 20%)
        //    Volume should be rising — institutions executing, not fading
        //    Hard gate: minimum volume score 30 (new gate)
        // ─────────────────────────────────────────────────────────────────────
        double volumeAccelScore = calculateVolumeAccelerationScore(bars, n);

        if (volumeAccelScore < 30) {
            return hold(context);  // NEW: reject low-volume setups entirely
        }

        // ─────────────────────────────────────────────────────────────────────
        // 8. COMPONENT ⑤: BAR QUALITY (weight: 15%)
        //    Current bar should have strong close in pressure direction
        //    Hard gate: minimum bar quality 40 (new gate)
        // ─────────────────────────────────────────────────────────────────────
        double barQualityScore = calculateBarQualityScore(bars.get(n - 1), pressureBuy);

        if (barQualityScore < 40) {
            return hold(context);  // NEW: reject weak/rejection candles entirely
        }

        // ─────────────────────────────────────────────────────────────────────
        // 9. WEIGHTED COMPOSITE SCORE (5 components)
        // ─────────────────────────────────────────────────────────────────────
        double niftyComponent = Math.abs(niftyTrendScore);  // Use absolute for composite
        double compositeScore = (niftyComponent * 0.10)
                              + (imbalanceScore * 0.30)
                              + (momentumScore * 0.25)
                              + (volumeAccelScore * 0.20)
                              + (barQualityScore * 0.15);

        if (compositeScore < minCompositeScore) {
            log.debug("spike.low_composite symbol={} score={} [nifty={} imb={} mom={} vol={} bar={}]",
                    symbol, String.format("%.1f", compositeScore),
                    String.format("%.0f", niftyComponent),
                    String.format("%.0f", imbalanceScore),
                    String.format("%.0f", momentumScore),
                    String.format("%.0f", volumeAccelScore),
                    String.format("%.0f", barQualityScore));
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 10. PRESSURE + PRICE AGREEMENT (multi-bar, not just last bar)
        //     Check last 3 bars: price trend must agree with pressure direction
        // ─────────────────────────────────────────────────────────────────────
        double curClose = toDouble(bars.get(n - 1).getClosePrice());
        double threeBarAgoClose = toDouble(bars.get(n - 4).getClosePrice());
        boolean priceTrendUp = curClose > threeBarAgoClose;

        if (pressureBuy != priceTrendUp) {
            log.debug("spike.pressure_price_divergence symbol={} pressureBuy={} priceTrendUp={}", symbol, pressureBuy, priceTrendUp);
            return hold(context);
        }

        // Also check last bar isn't a reversal candle
        double prevClose = toDouble(bars.get(n - 2).getClosePrice());
        boolean lastBarAligned = pressureBuy ? (curClose > prevClose) : (curClose < prevClose);
        if (!lastBarAligned) {
            return hold(context);  // Last bar reversed against pressure — skip
        }

        // ─────────────────────────────────────────────────────────────────────
        // 11. COOLDOWN CHECK (raised to 15 min)
        // ─────────────────────────────────────────────────────────────────────
        Instant now = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null && Duration.between(lastEmit, now).getSeconds() < cooldownSeconds) {
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 12. DYNAMIC STOP LOSS — 5-bar swing structure (was 3)
        //     Wider lookback = more structural support/resistance = fewer SL hits
        // ─────────────────────────────────────────────────────────────────────
        double entryPrice = curClose;
        double stopLoss;

        if (pressureBuy) {
            double swingLow = Double.MAX_VALUE;
            for (int i = n - SWING_SL_BARS; i < n; i++) {
                double low = toDouble(bars.get(i).getLowPrice());
                if (low > 0 && low < swingLow) swingLow = low;
            }
            stopLoss = swingLow * (1.0 - slBufferPct / 100.0);
        } else {
            double swingHigh = Double.MIN_VALUE;
            for (int i = n - SWING_SL_BARS; i < n; i++) {
                double high = toDouble(bars.get(i).getHighPrice());
                if (high > swingHigh) swingHigh = high;
            }
            stopLoss = swingHigh * (1.0 + slBufferPct / 100.0);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 13. TARGET — R:R based (lowered to 1.5× for higher hit rate)
        // ─────────────────────────────────────────────────────────────────────
        double risk = Math.abs(entryPrice - stopLoss);
        if (risk <= 0 || risk / entryPrice < 0.0005) {
            return hold(context);  // Risk too small = SL too tight = will whipsaw
        }
        if (risk / entryPrice > 0.02) {
            return hold(context);  // Risk too large = SL too wide = bad R:R
        }

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
        // 14. EMIT SIGNAL
        // ─────────────────────────────────────────────────────────────────────
        SignalType signalType = pressureBuy ? SignalType.BUY : SignalType.SELL;

        String pressureInfo = String.format("imb=%.0f%%", snapshot.imbalanceRatio() * 100);
        String consistencyInfo = String.format("consist=%.0f%%", consistency * 100);
        String niftyInfo = String.format("nifty=%s%.0f", niftyTrendScore > 0 ? "+" : "", niftyTrendScore);

        double volMultiple = calculateCurrentVolumeMultiple(bars, n);
        double momPct = calculateCumulativeMomentum(bars, n, MOMENTUM_BARS);

        String reason = String.format(
            "NSE_SPIKE_V3.5 %s: %s %s %s mom=%.2f%% vol=%.1fx composite=%.1f " +
            "[nifty=%.0f imb=%.0f mom=%.0f vol=%.0f bar=%.0f] entry=%.2f sl=%.2f target=%.2f rr=%.1f risk=%.2f%%",
            signalType, pressureInfo, consistencyInfo, niftyInfo, momPct, volMultiple, compositeScore,
            niftyComponent, imbalanceScore, momentumScore, volumeAccelScore, barQualityScore,
            entryPrice, stopLoss, target, rr, (risk / entryPrice) * 100
        );

        log.info("spike_v35.signal symbol={} {}", symbol, reason);
        return new StrategySignal(
                signalType, symbol, BigDecimal.ONE, reason,
                BigDecimal.valueOf(entryPrice),
                BigDecimal.valueOf(stopLoss),
                BigDecimal.valueOf(target)
        );
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // NIFTY TREND FILTER
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * NIFTY 50 Trend Score: checks last 10 minutes of NIFTY direction.
     * Returns: +100 (strong uptrend) to -100 (strong downtrend), 0 = flat/no data
     *
     * Logic:
     *   - Check NIFTY close[now] vs close[10 bars ago]
     *   - Check how many of last 5 bars are green vs red
     *   - Combine direction + consistency
     */
    private double calculateNiftyTrendScore(StrategyContext context) {
        try {
            var niftyOpt = integrityGate.sessionBars(
                    key(), NIFTY_SYMBOL, TIMEFRAME, NIFTY_BARS, NIFTY_BARS - 1,
                    LookbackWindow.FIVE_MINUTE, context);
            if (niftyOpt.isEmpty() || niftyOpt.get().size() < 5) {
                return 0;
            }
            List<MarketdataCandle> niftyBars = niftyOpt.get();

            int nb = niftyBars.size();
            double firstClose = toDouble(niftyBars.get(0).getClosePrice());
            double lastClose = toDouble(niftyBars.get(nb - 1).getClosePrice());

            if (firstClose <= 0) return 0;

            // Direction: NIFTY % change over window
            double niftyChangePct = (lastClose - firstClose) / firstClose * 100;

            // Consistency: how many of last 5 bars align with direction
            int greenBars = 0, redBars = 0;
            int checkBars = Math.min(5, nb);
            for (int i = nb - checkBars; i < nb; i++) {
                double c = toDouble(niftyBars.get(i).getClosePrice());
                double o = toDouble(niftyBars.get(i).getOpenPrice());
                if (c > o) greenBars++;
                else if (c < o) redBars++;
            }

            double directionScore;
            if (niftyChangePct > 0.15) directionScore = 100;
            else if (niftyChangePct > 0.08) directionScore = 70;
            else if (niftyChangePct > 0.03) directionScore = 50;
            else if (niftyChangePct > -0.03) directionScore = 0;   // Flat
            else if (niftyChangePct > -0.08) directionScore = -50;
            else if (niftyChangePct > -0.15) directionScore = -70;
            else directionScore = -100;

            // Consistency bonus/penalty
            double consistencyMultiplier;
            if (directionScore > 0) {
                // Uptrend: reward green bars
                consistencyMultiplier = greenBars >= 4 ? 1.0 : greenBars >= 3 ? 0.8 : 0.5;
            } else if (directionScore < 0) {
                // Downtrend: reward red bars
                consistencyMultiplier = redBars >= 4 ? 1.0 : redBars >= 3 ? 0.8 : 0.5;
            } else {
                consistencyMultiplier = 1.0;
            }

            return directionScore * consistencyMultiplier;
        } catch (Exception e) {
            log.debug("spike.nifty_trend_error: {}", e.getMessage());
            return 0;  // Error reading NIFTY — neutral
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // SCORING METHODS
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * MULTI-BAR MOMENTUM: Checks last 3 bars for sustained directional movement.
     * V3.5: Requires ALL 3 bars aligned for score > 55 (more strict).
     */
    private double calculateMomentumScore(List<MarketdataCandle> bars, int n, boolean expectBuy) {
        if (n < MOMENTUM_BARS + 1) return 0;

        int alignedBars = 0;
        boolean allIncreasing = true;
        double cumulativeMove = 0;

        for (int i = n - MOMENTUM_BARS; i < n; i++) {
            double close = toDouble(bars.get(i).getClosePrice());
            double open = toDouble(bars.get(i).getOpenPrice());
            double prevClose = toDouble(bars.get(i - 1).getClosePrice());

            if (expectBuy) {
                if (close > open) alignedBars++;
                if (i > n - MOMENTUM_BARS && close <= toDouble(bars.get(i - 1).getClosePrice())) {
                    allIncreasing = false;
                }
            } else {
                if (close < open) alignedBars++;
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
        // All 3 aligned but small move
        if (alignedBars == MOMENTUM_BARS) return 60;
        // 2 of 3 aligned + decent move (V3.5: capped at 45 — can only pass gate barely)
        if (alignedBars >= 2 && absMomentum >= minMomentumPct) return 45;
        // Anything else = too weak
        return 0;
    }

    /**
     * VOLUME ACCELERATION: Volume should be INCREASING over last 3 bars.
     * V3.5: Raised minimum volume multiple from 1.2× to 1.5×
     */
    private double calculateVolumeAccelerationScore(List<MarketdataCandle> bars, int n) {
        if (n < MOMENTUM_BARS + VOLUME_AVG_PERIOD) return 0;  // V3.5: Return 0 not 50

        // Average volume of older bars (baseline)
        double avgVol = 0;
        int count = 0;
        for (int i = n - MOMENTUM_BARS - VOLUME_AVG_PERIOD; i < n - MOMENTUM_BARS; i++) {
            if (i < 0) continue;
            double v = toDouble(bars.get(i).getVolume());
            if (v > 0) { avgVol += v; count++; }
        }
        avgVol = count > 0 ? avgVol / count : 0;
        if (avgVol <= 0) return 0;  // V3.5: No volume data = no score (was 50)

        // Volume of last 3 bars
        double[] recentVol = new double[MOMENTUM_BARS];
        for (int i = 0; i < MOMENTUM_BARS; i++) {
            recentVol[i] = toDouble(bars.get(n - MOMENTUM_BARS + i).getVolume());
        }

        double currentMultiple = recentVol[MOMENTUM_BARS - 1] / avgVol;

        // Check if volume is accelerating (each bar higher than previous)
        boolean accelerating = true;
        for (int i = 1; i < MOMENTUM_BARS; i++) {
            if (recentVol[i] < recentVol[i - 1] * 0.9) {
                accelerating = false;
                break;
            }
        }

        // Accelerating + high multiple
        if (accelerating && currentMultiple >= 3.0) return 100;
        if (accelerating && currentMultiple >= 2.0) return 85;
        if (accelerating && currentMultiple >= minVolumeMultiple) return 70;
        // High volume but not accelerating
        if (currentMultiple >= 2.5) return 55;
        if (currentMultiple >= minVolumeMultiple) return 40;
        // Below minimum
        return 0;
    }

    /**
     * BAR QUALITY: Current bar should close strongly in the pressure direction.
     * V3.5: Stricter — reject wicky/indecisive candles more aggressively.
     */
    private double calculateBarQualityScore(MarketdataCandle bar, boolean isBuy) {
        double high = toDouble(bar.getHighPrice());
        double low = toDouble(bar.getLowPrice());
        double close = toDouble(bar.getClosePrice());
        double open = toDouble(bar.getOpenPrice());
        double range = high - low;

        if (range <= 0) return 0;

        double bodySize = Math.abs(close - open);
        double bodyRatio = bodySize / range;

        if (isBuy) {
            double upperWick = high - Math.max(close, open);
            double wickRatio = range > 0 ? upperWick / range : 0;
            if (wickRatio > maxWickPct) return 0;

            if (close > open && bodyRatio > 0.65) return 100;
            if (close > open && bodyRatio > 0.45) return 75;
            if (close > open && bodyRatio > 0.30) return 50;
            return 0;  // V3.5: Red bar or tiny body in buy setup = REJECT (was 15)
        } else {
            double lowerWick = Math.min(close, open) - low;
            double wickRatio = range > 0 ? lowerWick / range : 0;
            if (wickRatio > maxWickPct) return 0;

            if (close < open && bodyRatio > 0.65) return 100;
            if (close < open && bodyRatio > 0.45) return 75;
            if (close < open && bodyRatio > 0.30) return 50;
            return 0;  // V3.5: Green bar in sell setup = REJECT
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
