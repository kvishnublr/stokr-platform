package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureSnapshot;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S3_VWAP_RETEST — VWAP Retest Continuation Strategy
 *
 * EXACT PORT of Python S3 from nse_edge_live_v/backend/intraday_engine/.
 *
 * Python definition:
 *   StrategyDef("S3", "VWAP Retest Continuation", "TREND", "10:00", "13:00",
 *               0.70, "BOTH", -0.25, 0.25, sl_pct=0.35, tgt_pct=0.60)
 *
 * Detection logic from core.py:
 *   - Regime: TREND only (ORB break + breadth >= 0.60)
 *   - VWAP extension: must be between -0.25% and +0.25% (retest zone)
 *   - Weighted composite scoring:
 *       lead_lag(0.25) + volume(0.25) + velocity(0.20) + breadth(0.15) + time(0.15)
 *   - Min score: 0.70 to enter, 0.80 for full size
 *   - Direction: BOTH (BULL or BEAR based on lead-lag detector)
 *
 * Time window: 10:00 - 13:00 IST
 * SL = 0.35%, Target = 0.60%
 *
 * Time score windows (from config.py TIME_WINDOWS):
 *   09:20-10:15 → 1.00, 10:15-11:00 → 0.75, 13:30-14:30 → 0.70,
 *   11:00-13:30 → 0.20, 14:30-15:15 → 0.30
 *
 * DATA SOURCE MAPPING:
 *   lead_lag  → OrderBookPressureTracker imbalanceRatio (proxy for bank stock lead-lag)
 *   volume    → 5m volume acceleration vs baseline
 *   velocity  → tick rate proxy (bars with increasing volume = higher velocity)
 *   breadth   → percentage of last N bars in same direction (momentum breadth proxy)
 *   time      → time window score from config
 *   VWAP      → computed from intraday candles (sum(typical*vol)/sum(vol))
 *   regime    → approximated from ORB break + momentum breadth
 */
@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "S3_VWAP_RETEST",
    assetClass   = "FUTURES",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class S3VwapRetestSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "1m";
    private static final int BARS_FETCH = 60;  // Need 50+ for VWAP accuracy

    // ── Python exact constants ──
    // From strategies.py: sl_pct=0.35, tgt_pct=0.60
    private static final BigDecimal SL_PERCENT     = BigDecimal.valueOf(0.0035);  // 0.35%
    private static final BigDecimal TARGET_PERCENT  = BigDecimal.valueOf(0.0060);  // 0.60%

    // From strategies.py: ext_min=-0.25, ext_max=0.25
    private static final double VWAP_EXT_MIN = -0.25;
    private static final double VWAP_EXT_MAX = 0.25;

    // From strategies.py: min_score=0.70
    private static final double ENTRY_THRESHOLD  = 0.70;
    private static final double FULL_SIZE_THRESH = 0.80;

    // From strategies.py: earliest="10:00", latest="13:00"
    private static final int TRADING_START_MIN = 600;  // 10:00
    private static final int TRADING_END_MIN   = 780;  // 13:00

    // From strategies.py: regime="TREND"
    // Regime classification constants (from config.py)
    private static final double ORB_BREAK_PCT      = 0.50;
    private static final double BREADTH_TREND_MIN  = 0.60;

    // Weighted scorer weights (from config.py WEIGHTS)
    private static final double W_LEAD_LAG = 0.25;
    private static final double W_VOLUME   = 0.25;
    private static final double W_VELOCITY = 0.20;
    private static final double W_BREADTH  = 0.15;
    private static final double W_TIME     = 0.15;

    private final MarketDataQueryService marketDataQueryService;
    private final OrderBookPressureTracker pressureTracker;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.s3vwap.cooldown-seconds:900}")
    private int cooldownSeconds;

    @Override
    public String key() { return "S3_VWAP_RETEST"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();

        // ─── Time Window Check (10:00–13:00 IST) ───
        LocalTime now;
        if (context.asOf() != null) {
            now = context.asOf().atZone(zone).toLocalTime();
        } else {
            now = LocalTime.now(zone);
        }
        int currentMin = now.getHour() * 60 + now.getMinute();
        if (currentMin < TRADING_START_MIN || currentMin > TRADING_END_MIN) {
            return hold(context);
        }

        // ─── Load candle data ───
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(symbol, TIMEFRAME, BARS_FETCH);
        if (bars.size() < 21) {
            return hold(context);
        }
        int n = bars.size();
        MarketdataCandle latestBar = bars.get(n - 1);
        BigDecimal currentPrice = latestBar.getClosePrice();
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return hold(context);
        }

        // ─── Regime Classification (TREND only for S3) ───
        // Python: TREND = ORB break + breadth >= 0.60
        // Approximate ORB break: first 15-min range vs current price
        String regime = classifyRegime(bars, currentPrice);
        if (!"TREND".equals(regime) && !"MIXED".equals(regime)) {
            // S3 requires TREND regime. Also allow MIXED as fallback (Python: MIXED active=["S3","S6","S7"])
            return hold(context);
        }

        // ─── Compute VWAP (exact Python VWAPTracker logic) ───
        // VWAP = sum(typical_price * volume) / sum(volume)
        BigDecimal sumPV = BigDecimal.ZERO;
        BigDecimal sumV = BigDecimal.ZERO;
        for (MarketdataCandle bar : bars) {
            BigDecimal high = bar.getHighPrice();
            BigDecimal low = bar.getLowPrice();
            BigDecimal close = bar.getClosePrice();
            BigDecimal vol = bar.getVolume();
            if (high == null || low == null || close == null || vol == null || vol.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal typicalPrice = high.add(low).add(close).divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);
            sumPV = sumPV.add(typicalPrice.multiply(vol));
            sumV = sumV.add(vol);
        }
        BigDecimal vwap = sumV.compareTo(BigDecimal.ZERO) > 0
                ? sumPV.divide(sumV, 6, RoundingMode.HALF_UP)
                : currentPrice;

        // ─── VWAP Extension Check (Python: ext_min=-0.25, ext_max=0.25) ───
        // extension_pct = (price - vwap) / vwap * 100
        double extensionPct = 0;
        if (vwap.compareTo(BigDecimal.ZERO) > 0) {
            extensionPct = currentPrice.subtract(vwap)
                    .divide(vwap, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100.0;
        }
        if (extensionPct < VWAP_EXT_MIN || extensionPct > VWAP_EXT_MAX) {
            return hold(context);
        }

        // ─── Compute weighted composite score components ───

        // 1. Lead-lag score (from pressure tracker as proxy)
        double leadLagScore = 0.0;
        PressureSnapshot snapshot = pressureTracker.getSnapshot(symbol);
        if (snapshot != null) {
            // Map imbalance (0-1) to lead-lag score (0-1)
            // High imbalance in one direction = strong lead signal
            leadLagScore = Math.abs(snapshot.imbalanceRatio() - 0.5) * 2.0;
        }

        // 2. Volume acceleration score
        double volumeScore = computeVolumeScore(bars, n);

        // 3. Velocity score (tick rate proxy — bars with increasing volume)
        double velocityScore = computeVelocityScore(bars, n, currentMin);

        // 4. Breadth score (% of recent bars moving in same direction)
        double breadthScore = computeBreadthScore(bars, n);

        // 5. Time window score (from Python config.py TIME_WINDOWS)
        double timeScore = computeTimeScore(currentMin);

        // ─── Weighted composite (exact Python WeightedScorer) ───
        double composite = leadLagScore * W_LEAD_LAG
                         + volumeScore  * W_VOLUME
                         + velocityScore * W_VELOCITY
                         + breadthScore * W_BREADTH
                         + timeScore    * W_TIME;
        composite = Math.round(composite * 10000.0) / 10000.0;

        // ─── Score threshold check (Python: min_score=0.70) ───
        if (composite < ENTRY_THRESHOLD) {
            return hold(context);
        }

        // ─── Determine direction (Python: side="BOTH") ───
        // BULL if lead-lag suggests buying, BEAR if selling
        String direction;
        SignalType signalType;
        if (snapshot != null) {
            if (snapshot.imbalanceRatio() > 0.5) {
                direction = "BULL";
                signalType = SignalType.BUY;
            } else {
                direction = "BEAR";
                signalType = SignalType.SELL;
            }
        } else {
            // Fallback: use price vs VWAP
            if (currentPrice.compareTo(vwap) > 0) {
                direction = "BULL";
                signalType = SignalType.BUY;
            } else {
                direction = "BEAR";
                signalType = SignalType.SELL;
            }
        }

        // ─── Lot multiplier (Python: >= 0.80 → 1.0, >= 0.70 → 0.75) ───
        double lotMult = composite >= FULL_SIZE_THRESH ? 1.0 : 0.75;

        // ─── Cooldown ───
        Instant evalTime = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null && Duration.between(lastEmit, evalTime).getSeconds() < cooldownSeconds) {
            return hold(context);
        }

        // ─── Entry/Exit Levels (exact Python: sl_pct=0.35, tgt_pct=0.60) ───
        BigDecimal entryLevel = currentPrice;
        BigDecimal targetLevel;
        BigDecimal stopLoss;

        if ("BULL".equals(direction)) {
            targetLevel = entryLevel.multiply(BigDecimal.ONE.add(TARGET_PERCENT));
            stopLoss    = entryLevel.multiply(BigDecimal.ONE.subtract(SL_PERCENT));
        } else {
            targetLevel = entryLevel.multiply(BigDecimal.ONE.subtract(TARGET_PERCENT));
            stopLoss    = entryLevel.multiply(BigDecimal.ONE.add(SL_PERCENT));
        }

        lastEmitBySymbol.put(symbol, evalTime);

        String pressureInfo = snapshot != null ? String.format(" imb=%.0f%%", snapshot.imbalanceRatio() * 100) : "";

        String reason = String.format(
            "S3_VWAP_RETEST %s: price=%.2f vwap=%.2f ext=%.3f%% " +
            "ll=%.4f vol=%.4f vel=%.4f brd=%.4f time=%.4f " +
            "composite=%.4f regime=%s lot=%.2f%s entry=%.2f target=%.2f sl=%.2f",
            direction, currentPrice, vwap, extensionPct,
            leadLagScore, volumeScore, velocityScore, breadthScore, timeScore,
            composite, regime, lotMult, pressureInfo,
            entryLevel, targetLevel, stopLoss
        );

        log.info("s3_vwap_retest.signal symbol={} {}", symbol, reason);

        return new StrategySignal(signalType, symbol, BigDecimal.valueOf(lotMult), reason,
                entryLevel,
                stopLoss.setScale(2, RoundingMode.HALF_UP),
                targetLevel.setScale(2, RoundingMode.HALF_UP));
    }

    // ────────────────────────────────────────────────────────────────────
    // Python-exact helper methods
    // ────────────────────────────────────────────────────────────────────

    /**
     * Regime classification (approximation of Python RegimeClassifier).
     * TREND = ORB break + breadth >= 0.60
     * CHOP = no break + breadth < 0.35
     * MIXED = fallback
     */
    private String classifyRegime(List<MarketdataCandle> bars, BigDecimal currentPrice) {
        if (bars.size() < 16) return "MIXED";

        // ORB: first 15 bars (9:15-9:30 approximate)
        double orbHi = Double.MIN_VALUE;
        double orbLo = Double.MAX_VALUE;
        int orbBars = Math.min(15, bars.size());
        for (int i = 0; i < orbBars; i++) {
            double h = toDouble(bars.get(i).getHighPrice());
            double l = toDouble(bars.get(i).getLowPrice());
            if (h > orbHi) orbHi = h;
            if (l > 0 && l < orbLo) orbLo = l;
        }
        double orbRange = orbHi - orbLo;
        boolean orbBreak = orbRange > 0 && toDouble(currentPrice) > (orbHi + orbRange * ORB_BREAK_PCT);

        // Breadth: % of recent bars going up
        int upBars = 0;
        int totalBars = 0;
        for (int i = Math.max(0, bars.size() - 20); i < bars.size(); i++) {
            BigDecimal c = bars.get(i).getClosePrice();
            BigDecimal o = bars.get(i).getOpenPrice();
            if (c != null && o != null) {
                totalBars++;
                if (c.compareTo(o) > 0) upBars++;
            }
        }
        double breadth = totalBars > 0 ? (double) upBars / totalBars : 0.5;

        if (orbBreak && breadth >= BREADTH_TREND_MIN) return "TREND";
        if (!orbBreak && breadth < 0.35) return "CHOP";
        return "MIXED";
    }

    /**
     * Volume acceleration score (from Python VolumeAccelDetector).
     * Recent 5-bar vol vs earlier baseline — ratio mapped to 0-1.
     */
    private double computeVolumeScore(List<MarketdataCandle> bars, int n) {
        if (n < 10) return 0.0;
        double recentVol = 0;
        for (int i = n - 5; i < n; i++) {
            BigDecimal v = bars.get(i).getVolume();
            if (v != null) recentVol += v.doubleValue();
        }
        double baseVol = 0;
        int baseCount = 0;
        for (int i = 0; i < n - 5; i++) {
            BigDecimal v = bars.get(i).getVolume();
            if (v != null) {
                baseVol += v.doubleValue();
                baseCount++;
            }
        }
        if (baseCount == 0 || baseVol <= 0) return 0.0;
        double baseMean = baseVol / baseCount;
        double recentMean = recentVol / 5.0;
        double ratio = recentMean / Math.max(baseMean, 1.0);
        // Python VolumeAccelDetector: (ratio - 1.0) / mult, clamped 0-1
        // Using mult=2.0 (VOL_MULT_NORMAL from config.py)
        if (ratio < 2.0) return 0.0;
        return Math.min(1.0, Math.max(0.0, (ratio - 1.0) / 2.0));
    }

    /**
     * Velocity score (from Python VelocityDetector).
     * Based on volume acceleration in recent bars — higher volume = more ticks.
     */
    private double computeVelocityScore(List<MarketdataCandle> bars, int n, int currentMin) {
        if (n < 3) return 0.0;
        // Approximate velocity from volume growth in last 3 bars
        BigDecimal v1 = bars.get(n - 3).getVolume();
        BigDecimal v2 = bars.get(n - 2).getVolume();
        BigDecimal v3 = bars.get(n - 1).getVolume();
        if (v1 == null || v2 == null || v3 == null) return 0.0;
        double d1 = v1.doubleValue();
        double d2 = v2.doubleValue();
        double d3 = v3.doubleValue();
        if (d1 <= 0) return 0.0;
        double accel = d3 / Math.max(d1, 1.0);
        // Python: vel = min(1.0, one / max(expected, 1)); expected=15 for early, 10/7 for later
        int expected = currentMin < 600 ? 15 : (currentMin < 720 ? 10 : 7);
        double vel = Math.min(1.0, accel / expected * 5.0);
        // Acceleration bonus
        double accelRatio = d3 / Math.max((d1 + d2 + d3) / 3.0, 1e-9);
        double bonus = accelRatio > 1.3 ? 0.20 : 0.0;  // VEL_ACCEL_THRESH=1.3
        return Math.min(1.0, vel + bonus);
    }

    /**
     * Breadth score (from Python LeadLagDetector.score()).
     * % of recent bars moving in the dominant direction.
     */
    private double computeBreadthScore(List<MarketdataCandle> bars, int n) {
        int lookback = Math.min(20, n);
        int bull = 0, bear = 0;
        for (int i = n - lookback; i < n; i++) {
            BigDecimal c = bars.get(i).getClosePrice();
            BigDecimal o = bars.get(i).getOpenPrice();
            if (c != null && o != null) {
                if (c.compareTo(o) > 0) bull++;
                else if (c.compareTo(o) < 0) bear++;
            }
        }
        int aligned = Math.max(bull, bear);
        return (double) aligned / Math.max(lookback, 1);
    }

    /**
     * Time window score (exact Python config.py TIME_WINDOWS).
     * 09:20-10:15 → 1.00, 10:15-11:00 → 0.75, 13:30-14:30 → 0.70,
     * 11:00-13:30 → 0.20, 14:30-15:15 → 0.30
     */
    private double computeTimeScore(int currentMin) {
        if (currentMin >= 560 && currentMin < 615) return 1.00;   // 09:20-10:15
        if (currentMin >= 615 && currentMin < 660) return 0.75;   // 10:15-11:00
        if (currentMin >= 810 && currentMin < 870) return 0.70;   // 13:30-14:30
        if (currentMin >= 660 && currentMin < 810) return 0.20;   // 11:00-13:30
        if (currentMin >= 870 && currentMin < 915) return 0.30;   // 14:30-15:15
        return 0.0;
    }

    private static double toDouble(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
