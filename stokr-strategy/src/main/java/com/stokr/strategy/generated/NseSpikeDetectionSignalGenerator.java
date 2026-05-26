package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
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
 * NSE_SPIKE_DETECTION V2.0 - PURE DATA-DRIVEN INSTITUTIONAL STRATEGY
 *
 * Philosophy: Zero lagging indicators. Only raw price/volume structure.
 * Removed: RSI, EMA, VWAP, fixed ATR multiples, index/sector alignment
 *
 * Entry Logic:
 *   1. Spike Score (3 components, each 0-100):
 *      - Velocity Score: % move per minute (0.25%+ = spike)
 *      - Volume Score: Burst multiplier vs 20-bar average
 *      - Bar Quality Score: Close position in range (rejection = low score)
 *
 *   2. Composite Score = (velocity + volume + barQuality) / 3
 *
 *   3. Fire if: composite_score >= 75 AND continuation_confirmed AND no_wick_rejection
 *
 * Stop Loss: DYNAMIC
 *   - Not fixed ATR multiple
 *   - Calculated as: Entry bar low/high ± 0.50%
 *   - Adapts to actual bar structure, not pre-calculated volatility
 *
 * Target: DYNAMIC RETEST-BASED
 *   - Based on: Range of last 5 bars structure
 *   - Tight ranges (<1.0%): target = recent extreme ± 0.50% extension
 *   - Wide ranges (>1.0%): target = recent extreme ± 1.50% extension
 *
 * Session: 09:15-15:20 IST (core trading hours)
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
    private static final int BARS_FETCH = 25;
    private static final int VOLUME_AVG_PERIOD = 20;
    private static final int RETEST_LOOKBACK = 5;
    private static final int REGIME_LOOKBACK = 10;

    private final StrategyCandleLoader candleLoader;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    // ═══════════════════════════════════════════════════════════════════════════
    // PURE DATA PARAMETERS (no lagging indicators)
    // ═══════════════════════════════════════════════════════════════════════════

    // VELOCITY: Minimum % move per minute to qualify as spike
    @Value("${stokr.strategy.spike.min-velocity-pct:0.12}")
    private double minVelocityPct;

    // VOLUME: Minimum multiplier vs 20-bar average to confirm conviction
    @Value("${stokr.strategy.spike.min-volume-multiple:1.2}")
    private double minVolumeMultiple;

    // BAR QUALITY: Close must be in upper/lower portion (not wicked)
    @Value("${stokr.strategy.spike.min-bar-quality-threshold:60.0}")
    private double minBarQualityThreshold;

    // WICK REJECTION: % of bar that is wick (not body)
    @Value("${stokr.strategy.spike.max-wick-pct-before-reject:0.70}")
    private double maxWickPctBeforeReject;

    // CONTINUATION: Next candle must continue in spike direction
    @Value("${stokr.strategy.spike.require-continuation-candle:true}")
    private boolean requireContinuationCandle;

    // SCORE THRESHOLD: Composite score must be >= this to fire
    @Value("${stokr.strategy.spike.min-composite-score:65.0}")
    private double minCompositeScore;

    // COOLDOWN: Seconds between signals on same symbol
    @Value("${stokr.strategy.spike.cooldown-seconds:300}")
    private int cooldownSeconds;

    @Value("${stokr.strategy.spike.min-risk-reward:1.2}")
    private double minRiskReward;

    @Value("${stokr.strategy.spike.regime-min-range-pct:0.60}")
    private double regimeMinRangePct;

    @Value("${stokr.strategy.spike.regime-min-directional-bars:6}")
    private int regimeMinDirectionalBars;

    @Value("${stokr.strategy.spike.require-prev-bar-alignment:true}")
    private boolean requirePrevBarAlignment;

    // ═══════════════════════════════════════════════════════════════════════════
    // DYNAMIC STOP LOSS PARAMETERS
    // ═══════════════════════════════════════════════════════════════════════════

    @Value("${stokr.strategy.spike.sl-offset-pct:0.50}")
    private double slOffsetPct;

    // ═══════════════════════════════════════════════════════════════════════════
    // DYNAMIC TARGET PARAMETERS (retest-based)
    // ═══════════════════════════════════════════════════════════════════════════

    @Value("${stokr.strategy.spike.target-tight-range-extension:0.50}")
    private double targetTightRangeExtension;

    @Value("${stokr.strategy.spike.target-wide-range-extension:1.50}")
    private double targetWideRangeExtension;

    @Value("${stokr.strategy.spike.range-width-threshold-pct:1.0}")
    private double rangeWidthThreshold;

    @Override
    public String key() {
        return "NSE_SPIKE_DETECTION";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();

        // ─────────────────────────────────────────────────────────────────────
        // 1. SESSION GATE: 09:15-15:20 IST
        // ─────────────────────────────────────────────────────────────────────
        if (context.asOf() != null) {
            LocalTime lt = context.asOf().atZone(zone).toLocalTime();
            if (lt.isBefore(LocalTime.of(9, 15)) || lt.isAfter(LocalTime.of(15, 20))) {
                return hold(context);
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // 2. LOAD CANDLE DATA
        // ─────────────────────────────────────────────────────────────────────
        List<MarketdataCandle> bars = candleLoader.bars(context, TIMEFRAME, BARS_FETCH);
        int n = bars.size();
        if (n < 3) {
            log.debug("nse_spike.insufficient_bars symbol={} have={}", symbol, n);
            return hold(context);
        }

        MarketdataCandle current = bars.get(n - 1);
        MarketdataCandle prev = bars.get(n - 2);

        double curClose = toDouble(current.getClosePrice());
        double curHigh = toDouble(current.getHighPrice());
        double curLow = toDouble(current.getLowPrice());
        double curVolume = toDouble(current.getVolume());
        double prevClose = toDouble(prev.getClosePrice());
        double prevOpen = toDouble(prev.getOpenPrice());

        if (curClose <= 0 || prevClose <= 0) return hold(context);

        if (isChoppyRegime(bars, n)) {
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 3. PURE DATA COMPONENT 1: VELOCITY SCORE
        // ─────────────────────────────────────────────────────────────────────
        double velocityPct = Math.abs(curClose - prevClose) / prevClose * 100;
        double velocityScore = calculateVelocityScore(velocityPct);

        if (velocityScore < 30) {
            return hold(context);
        }

        boolean isBuySpike = curClose > prevClose;

        if (requirePrevBarAlignment) {
            boolean prevBullish = prevClose > prevOpen;
            boolean prevBearish = prevClose < prevOpen;
            if ((isBuySpike && !prevBullish) || (!isBuySpike && !prevBearish)) {
                return hold(context);
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // 4. PURE DATA COMPONENT 2: VOLUME SCORE
        //    Note: broker feed volume = tick count, not shares. Use lenient gate.
        // ─────────────────────────────────────────────────────────────────────
        double avgVolume = calculateAverageVolume(bars, n);
        double volumeMultiple = avgVolume > 0 ? curVolume / avgVolume : 1.0;
        double volumeScore = calculateVolumeScore(volumeMultiple);

        // Volume data is tick-count granularity — don't hard-reject on it
        if (volumeScore < 20) {
            log.debug("nse_spike.low_volume symbol={} multiple={:.2f}", symbol, volumeMultiple);
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 5. PURE DATA COMPONENT 3: BAR QUALITY SCORE (wick rejection check)
        // ─────────────────────────────────────────────────────────────────────
        double barQualityScore = calculateBarQualityScore(current, isBuySpike);

        if (barQualityScore <= 0) {
            log.debug("nse_spike.wick_reject symbol={} score={}", symbol, barQualityScore);
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 6. COMPOSITE SCORE
        // ─────────────────────────────────────────────────────────────────────
        double compositeScore = (velocityScore + volumeScore + barQualityScore) / 3.0;

        if (compositeScore < minCompositeScore) {
            log.debug("nse_spike.low_score symbol={} composite={:.1f} min={}", symbol, compositeScore, minCompositeScore);
            return hold(context);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 7. CONTINUATION CONFIRMATION
        // ─────────────────────────────────────────────────────────────────────
        if (requireContinuationCandle) {
            if (isBuySpike) {
                if (curHigh <= prevClose) {
                    log.debug("nse_spike.no_continuation_buy symbol={} high={:.2f} <= prevClose={:.2f}",
                              symbol, curHigh, prevClose);
                    return hold(context);
                }
            } else {
                if (curLow >= prevClose) {
                    log.debug("nse_spike.no_continuation_sell symbol={} low={:.2f} >= prevClose={:.2f}",
                              symbol, curLow, prevClose);
                    return hold(context);
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // 8. COOLDOWN CHECK
        // ─────────────────────────────────────────────────────────────────────
        Instant now = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null && Duration.between(lastEmit, now).getSeconds() < cooldownSeconds) {
            log.debug("nse_spike.cooldown_active symbol={}", symbol);
            return hold(context);
        }
        lastEmitBySymbol.put(symbol, now);

        // ─────────────────────────────────────────────────────────────────────
        // 9. SIGNAL GENERATION WITH DYNAMIC SL & TARGET
        // ─────────────────────────────────────────────────────────────────────
        SignalType signalType;
        double entryPrice, stopLoss, target;
        String reason;

        if (isBuySpike) {
            signalType = SignalType.BUY;
            entryPrice = curClose;
            stopLoss = calculateAdaptiveBuyStop(bars, n, curLow);
            target = calculateAdaptiveBuyTarget(bars, n, entryPrice, stopLoss);

            double rr = (target - entryPrice) / Math.max(0.0001, entryPrice - stopLoss);
            reason = String.format(
                "NSE_SPIKE BUY: velocity=%.2f%% volume=%.1fx barQuality=%.0f composite=%.1f " +
                "entry=%.2f sl=%.2f target=%.2f rr=%.2f",
                velocityPct, volumeMultiple, barQualityScore, compositeScore,
                entryPrice, stopLoss, target, rr
            );

        } else {
            signalType = SignalType.SELL;
            entryPrice = curClose;
            stopLoss = calculateAdaptiveSellStop(bars, n, curHigh);
            target = calculateAdaptiveSellTarget(bars, n, entryPrice, stopLoss);

            double rr = (entryPrice - target) / Math.max(0.0001, stopLoss - entryPrice);
            reason = String.format(
                "NSE_SPIKE SELL: velocity=%.2f%% volume=%.1fx barQuality=%.0f composite=%.1f " +
                "entry=%.2f sl=%.2f target=%.2f rr=%.2f",
                velocityPct, volumeMultiple, barQualityScore, compositeScore,
                entryPrice, stopLoss, target, rr
            );
        }

        if (isBuySpike) {
            double rr = (target - entryPrice) / Math.max(0.0001, entryPrice - stopLoss);
            if (rr < minRiskReward) {
                return hold(context);
            }
        } else {
            double rr = (entryPrice - target) / Math.max(0.0001, stopLoss - entryPrice);
            if (rr < minRiskReward) {
                return hold(context);
            }
        }

        log.info("nse_spike.signal symbol={} type={} reason={}", symbol, signalType, reason);
        return new StrategySignal(
                signalType,
                symbol,
                BigDecimal.ONE,
                reason,
                BigDecimal.valueOf(entryPrice),
                BigDecimal.valueOf(stopLoss),
                BigDecimal.valueOf(target)
        );
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // PURE DATA CALCULATION METHODS
    // ═════════════════════════════════════════════════════════════════════════════

    private double calculateVelocityScore(double velocityPct) {
        if (velocityPct < minVelocityPct) return 0;
        if (velocityPct < 0.12) return 35;
        if (velocityPct < 0.20) return 50;
        if (velocityPct < 0.30) return 65;
        if (velocityPct < 0.50) return 80;
        if (velocityPct < 0.80) return 90;
        return 100;
    }

    private double calculateVolumeScore(double volumeMultiple) {
        // Volume is tick-count granularity; be lenient
        if (volumeMultiple < minVolumeMultiple) return 0;
        if (volumeMultiple < 0.8) return 30;
        if (volumeMultiple < 1.2) return 50;
        if (volumeMultiple < 1.8) return 65;
        if (volumeMultiple < 3.0) return 80;
        return 100;
    }

    private double calculateBarQualityScore(MarketdataCandle candle, boolean isBuySpike) {
        double high = toDouble(candle.getHighPrice());
        double low = toDouble(candle.getLowPrice());
        double close = toDouble(candle.getClosePrice());
        double open = toDouble(candle.getOpenPrice());

        double barRange = high - low;
        if (barRange <= 0) return 50;

        if (isBuySpike) {
            double wickSize = high - close;
            double bodySize = close - open;
            double wickPct = bodySize > 0 ? wickSize / (wickSize + bodySize) : 0;

            if (wickPct > maxWickPctBeforeReject) {
                return 0;
            }

            double closePos = (close - low) / barRange * 100;
            if (closePos >= minBarQualityThreshold) return 100;
            if (closePos >= 50) return 50;
            return 0;

        } else {
            double wickSize = close - low;
            double bodySize = open - close;
            double wickPct = bodySize > 0 ? wickSize / (wickSize + bodySize) : 0;

            if (wickPct > maxWickPctBeforeReject) {
                return 0;
            }

            double closePos = (close - low) / barRange * 100;
            if (closePos <= (100 - minBarQualityThreshold)) return 100;
            if (closePos <= 50) return 50;
            return 0;
        }
    }

    private double calculateAverageVolume(List<MarketdataCandle> bars, int currentIndex) {
        int start = Math.max(0, currentIndex - VOLUME_AVG_PERIOD);
        double sum = 0;
        for (int i = start; i < currentIndex - 1; i++) {
            sum += toDouble(bars.get(i).getVolume());
        }
        return (currentIndex - start) > 0 ? sum / (currentIndex - start) : 0;
    }

    private double calculateBuyTarget(List<MarketdataCandle> bars, int currentIndex) {
        int lookbackStart = Math.max(0, currentIndex - 1 - RETEST_LOOKBACK);
        double rangeHigh = Double.NEGATIVE_INFINITY;
        double rangeLow = Double.POSITIVE_INFINITY;

        for (int i = lookbackStart; i < currentIndex - 1; i++) {
            double h = toDouble(bars.get(i).getHighPrice());
            double l = toDouble(bars.get(i).getLowPrice());
            if (h > rangeHigh) rangeHigh = h;
            if (l < rangeLow) rangeLow = l;
        }

        if (rangeHigh <= 0) return 0;

        double rangeWidthPct = (rangeHigh - rangeLow) / rangeHigh * 100;
        double extension = rangeWidthPct < rangeWidthThreshold ?
                          targetTightRangeExtension : targetWideRangeExtension;

        return rangeHigh * (1.0 + extension / 100.0);
    }

    private double calculateSellTarget(List<MarketdataCandle> bars, int currentIndex) {
        int lookbackStart = Math.max(0, currentIndex - 1 - RETEST_LOOKBACK);
        double rangeHigh = Double.NEGATIVE_INFINITY;
        double rangeLow = Double.POSITIVE_INFINITY;

        for (int i = lookbackStart; i < currentIndex - 1; i++) {
            double h = toDouble(bars.get(i).getHighPrice());
            double l = toDouble(bars.get(i).getLowPrice());
            if (h > rangeHigh) rangeHigh = h;
            if (l < rangeLow) rangeLow = l;
        }

        if (rangeLow <= 0) return 0;

        double rangeWidthPct = (rangeHigh - rangeLow) / rangeHigh * 100;
        double extension = rangeWidthPct < rangeWidthThreshold ?
                          targetTightRangeExtension : targetWideRangeExtension;

        return rangeLow * (1.0 - extension / 100.0);
    }

    private boolean isChoppyRegime(List<MarketdataCandle> bars, int currentIndex) {
        int start = Math.max(1, currentIndex - REGIME_LOOKBACK);
        if (currentIndex - start < 5) {
            return false;
        }

        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        int directionalBars = 0;

        for (int i = start; i < currentIndex; i++) {
            MarketdataCandle c = bars.get(i);
            double cHigh = toDouble(c.getHighPrice());
            double cLow = toDouble(c.getLowPrice());
            double cOpen = toDouble(c.getOpenPrice());
            double cClose = toDouble(c.getClosePrice());
            if (cHigh > high) high = cHigh;
            if (cLow < low) low = cLow;
            if (Math.abs(cClose - cOpen) > 0) directionalBars++;
        }

        if (high <= 0 || low <= 0 || high <= low) {
            return true;
        }
        double rangePct = (high - low) / high * 100.0;
        return rangePct < regimeMinRangePct || directionalBars < regimeMinDirectionalBars;
    }

    private double recentAverageRangePct(List<MarketdataCandle> bars, int currentIndex) {
        int start = Math.max(0, currentIndex - REGIME_LOOKBACK);
        double sum = 0.0;
        int count = 0;
        for (int i = start; i < currentIndex; i++) {
            double h = toDouble(bars.get(i).getHighPrice());
            double l = toDouble(bars.get(i).getLowPrice());
            double c = toDouble(bars.get(i).getClosePrice());
            if (h > 0 && l > 0 && c > 0 && h > l) {
                sum += (h - l) / c * 100.0;
                count++;
            }
        }
        return count == 0 ? 0.30 : sum / count;
    }

    private double calculateAdaptiveBuyStop(List<MarketdataCandle> bars, int currentIndex, double curLow) {
        double avgRangePct = recentAverageRangePct(bars, currentIndex);
        double dynamicOffset = Math.max(slOffsetPct, Math.min(1.20, avgRangePct * 0.9));
        return curLow * (1.0 - dynamicOffset / 100.0);
    }

    private double calculateAdaptiveSellStop(List<MarketdataCandle> bars, int currentIndex, double curHigh) {
        double avgRangePct = recentAverageRangePct(bars, currentIndex);
        double dynamicOffset = Math.max(slOffsetPct, Math.min(1.20, avgRangePct * 0.9));
        return curHigh * (1.0 + dynamicOffset / 100.0);
    }

    private double calculateAdaptiveBuyTarget(List<MarketdataCandle> bars, int currentIndex, double entryPrice, double stopLoss) {
        double structureTarget = calculateBuyTarget(bars, currentIndex);
        double risk = Math.max(0.0001, entryPrice - stopLoss);
        double rrTarget = entryPrice + (risk * Math.max(minRiskReward, 1.8));
        return Math.max(structureTarget, rrTarget);
    }

    private double calculateAdaptiveSellTarget(List<MarketdataCandle> bars, int currentIndex, double entryPrice, double stopLoss) {
        double structureTarget = calculateSellTarget(bars, currentIndex);
        double risk = Math.max(0.0001, stopLoss - entryPrice);
        double rrTarget = entryPrice - (risk * Math.max(minRiskReward, 1.8));
        return Math.min(structureTarget, rrTarget);
    }

    private static double toDouble(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }
}
