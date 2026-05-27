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
 * S7_RANGE_FADE — Range Fade Lower Strategy (SHORT only)
 *
 * Migrated from legacy S7RangeFadeDetector to catalog-driven architecture.
 * Preserves the EXACT same detection logic:
 *
 *   Gate 1: Price near upper range high (within 0.2% of 5m high)
 *   Gate 2: Momentum negative or neutral (momentum5m ≤ 0.005) — fade condition
 *   Gate 3: Range exists (5m high - low ≥ 0.0025)
 *   Gate 4: Volume present (5m volume ≥ 1000)
 *   Gate 5: Time check (not after 13:30 — too close to close)
 *   Quality: base 50 + proximity(25) + momentum weakness(20) + range(15) + volume(10), min 65
 *
 * Direction: SHORT ONLY (mean reversion from range high)
 * Constants: SL=0.25%, Target=0.45%
 * Time window: 10:15–13:45 IST (with 13:30 cutoff in Gate 5)
 *
 * DATA SOURCE MAPPING:
 *   data.recent5mHigh → max(high) of last 5 candles
 *   data.recent5mLow  → min(low) of last 5 candles
 *   data.momentum5m   → (close[now] - close[5 ago]) / close[5 ago]
 *   data.volume5m     → sum of last 5 candle volumes
 *
 * Backtest: 99.7% WR, 879 trades
 */
@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "S7_RANGE_FADE",
    assetClass   = "FUTURES",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class S7RangeFadeSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "1m";
    private static final int BARS_FETCH = 10;  // Need last ~5-10 bars

    // Original constants (EXACT match)
    private static final BigDecimal SL_PERCENT     = BigDecimal.valueOf(0.0025);  // 0.25%
    private static final BigDecimal TARGET_PERCENT  = BigDecimal.valueOf(0.0045);  // 0.45%
    private static final int MIN_VOLUME_5M          = 1000;
    private static final int MIN_QUALITY            = 65;

    // Original time windows (10:15=615 min, 13:45=825 min)
    private static final int TRADING_START_MIN = 615;
    private static final int TRADING_END_MIN   = 825;

    private final MarketDataQueryService marketDataQueryService;
    private final OrderBookPressureTracker pressureTracker;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.s7rangefade.cooldown-seconds:900}")
    private int cooldownSeconds;

    @Override
    public String key() { return "S7_RANGE_FADE"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();

        // ─── Time Window Check (10:15–13:45 IST) ───
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
        if (bars.size() < 6) {
            return hold(context);  // Need at least 6 bars
        }
        int n = bars.size();
        MarketdataCandle latestBar = bars.get(n - 1);
        BigDecimal currentPrice = latestBar.getClosePrice();
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return hold(context);
        }

        // ─── Compute 5m range high/low ───
        double high5 = Double.MIN_VALUE;
        double low5 = Double.MAX_VALUE;
        for (int i = Math.max(0, n - 5); i < n; i++) {
            double h = toDouble(bars.get(i).getHighPrice());
            double l = toDouble(bars.get(i).getLowPrice());
            if (h > high5) high5 = h;
            if (l > 0 && l < low5) low5 = l;
        }
        BigDecimal range5mHigh = BigDecimal.valueOf(high5);
        BigDecimal range5mLow = BigDecimal.valueOf(low5);

        // ─── Compute momentum5m ───
        BigDecimal closePrev = bars.get(n - 6).getClosePrice();
        BigDecimal momentum5m;
        if (closePrev != null && closePrev.compareTo(BigDecimal.ZERO) > 0) {
            momentum5m = currentPrice.subtract(closePrev)
                    .divide(closePrev, 6, RoundingMode.HALF_UP);
        } else {
            momentum5m = BigDecimal.ZERO;
        }

        // ─── GATE 1: Price near upper range (within 0.2% of 5m high) ───
        BigDecimal upperTolerance = range5mHigh.multiply(BigDecimal.valueOf(0.002));
        boolean nearRangeHigh = currentPrice.compareTo(range5mHigh.subtract(upperTolerance)) >= 0 &&
                currentPrice.compareTo(range5mHigh) <= 0;

        if (!nearRangeHigh) {
            return hold(context);
        }

        // ─── GATE 2: Momentum negative or neutral (fade condition) ───
        // Original: momentum > 0.005 = still too bullish, skip
        if (momentum5m.compareTo(BigDecimal.valueOf(0.005)) > 0) {
            return hold(context);
        }

        // ─── GATE 3: Range exists (not too tight) ───
        // Original: range5mHigh - range5mLow >= 0.0025
        BigDecimal range5m = range5mHigh.subtract(range5mLow);
        if (range5m.compareTo(BigDecimal.valueOf(0.0025)) < 0) {
            return hold(context);
        }

        // ─── GATE 4: Volume present ───
        BigDecimal volume5m = BigDecimal.ZERO;
        for (int i = Math.max(0, n - 5); i < n; i++) {
            BigDecimal v = bars.get(i).getVolume();
            if (v != null) volume5m = volume5m.add(v);
        }
        if (volume5m.compareTo(BigDecimal.valueOf(MIN_VOLUME_5M)) < 0) {
            return hold(context);
        }

        // ─── GATE 5: Time check (not after 13:30) ───
        // Original: minutes > 810 (13:30)
        if (currentMin > 810) {
            return hold(context);
        }

        // ─── Quality Score (same formula as original) ───
        BigDecimal qualityScore = calculateQualityScore(currentPrice, range5mHigh, range5mLow,
                momentum5m, volume5m);
        if (qualityScore.compareTo(BigDecimal.valueOf(MIN_QUALITY)) < 0) {
            return hold(context);
        }

        // ─── Cooldown ───
        Instant evalTime = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null && Duration.between(lastEmit, evalTime).getSeconds() < cooldownSeconds) {
            return hold(context);
        }

        // ─── Signal: SHORT only (mean reversion) ───
        BigDecimal entryLevel = currentPrice;
        BigDecimal stopLoss   = entryLevel.multiply(BigDecimal.ONE.add(SL_PERCENT));
        BigDecimal targetLevel = entryLevel.multiply(BigDecimal.ONE.subtract(TARGET_PERCENT));

        lastEmitBySymbol.put(symbol, evalTime);

        String pressureInfo = "";
        PressureSnapshot snapshot = pressureTracker.getSnapshot(symbol);
        if (snapshot != null) {
            pressureInfo = String.format(" imb=%.0f%%", snapshot.imbalanceRatio() * 100);
        }

        String reason = String.format(
            "S7_RANGE_FADE SHORT: price=%.2f rangeHi=%.2f rangeLo=%.2f " +
            "momentum=%.4f vol5m=%s quality=%.0f%s entry=%.2f target=%.2f sl=%.2f",
            currentPrice, range5mHigh, range5mLow,
            momentum5m, volume5m.toPlainString(), qualityScore, pressureInfo,
            entryLevel, targetLevel, stopLoss
        );

        log.info("s7_range_fade.signal symbol={} {}", symbol, reason);

        return new StrategySignal(SignalType.SELL, symbol, BigDecimal.ONE, reason,
                entryLevel,
                stopLoss.setScale(2, RoundingMode.HALF_UP),
                targetLevel.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Quality score — exact same formula as original S7RangeFadeDetector.
     * Base 50 + proximity to high(25) + momentum weakness(20) + range size(15) + volume(10)
     */
    private BigDecimal calculateQualityScore(BigDecimal currentPrice, BigDecimal high, BigDecimal low,
                                              BigDecimal momentum, BigDecimal volume) {
        BigDecimal score = BigDecimal.valueOf(50); // Base

        // Proximity to range high (±25 points) — closer to high = more fade potential
        BigDecimal distToHigh = high.subtract(currentPrice);
        BigDecimal maxDist = high.subtract(low);
        if (maxDist.compareTo(BigDecimal.ZERO) > 0) {
            // Original: distToHigh / maxDist * 25 (higher = farther from high = less score)
            // Wait — original code: score.add(proximity) where proximity = distToHigh/maxDist * 25
            // This means FARTHER from high = MORE points. But that's the original logic.
            BigDecimal proximity = distToHigh.divide(maxDist, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(25));
            score = score.add(proximity);
        }

        // Momentum weakness (±20 points) — negative momentum = more likely fade
        if (momentum.compareTo(BigDecimal.ZERO) < 0) {
            score = score.add(BigDecimal.valueOf(20));
        }

        // Range size (±15 points) — larger range = better setup
        BigDecimal range = high.subtract(low);
        if (range.compareTo(BigDecimal.valueOf(0.005)) > 0) {
            score = score.add(BigDecimal.valueOf(15));
        }

        // Volume confirmation (±10 points)
        if (volume.compareTo(BigDecimal.valueOf(5000)) > 0) {
            score = score.add(BigDecimal.valueOf(10));
        }

        return score.min(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private static double toDouble(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
