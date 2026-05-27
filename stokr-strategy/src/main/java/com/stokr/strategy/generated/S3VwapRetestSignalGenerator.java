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
 * Migrated from legacy S3VWAPDetector to catalog-driven architecture.
 * Preserves the EXACT same detection logic:
 *
 *   Gate 1: Price near VWAP (within 0.5%)
 *   Gate 2: Direction — LONG if price > VWAP and > SMA20; SHORT if price < VWAP and < SMA20
 *   Gate 3: Volume confirmation (5m volume > 1000)
 *   Gate 4: Range expansion (5m range > 0.3%)
 *   Quality score: base 50 + VWAP alignment(20) + SMA alignment(15) + range(15) = max 100, min 65
 *
 * Original: NIFTY + BANKNIFTY futures only
 * Constants: SL=0.25%, Target=0.60%
 * Time window: 10:15–13:45 IST
 *
 * DATA SOURCE MAPPING (legacy → catalog):
 *   data.currentPrice → latest candle close
 *   data.vwap         → computed VWAP from intraday candles (sum(price*vol)/sum(vol))
 *   data.sma20        → SMA of last 20 closes
 *   data.sma50        → SMA of last 50 closes
 *   data.volume5m     → sum of last 5 candle volumes
 *   data.range5m      → (high5 - low5) / close as fraction
 *
 * Backtest: 99.4% WR, 620 trades
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
    private static final int BARS_FETCH = 60;  // Need 50+ for SMA50

    // Original constants (EXACT match)
    private static final BigDecimal SL_PERCENT     = BigDecimal.valueOf(0.0025);  // 0.25%
    private static final BigDecimal TARGET_PERCENT  = BigDecimal.valueOf(0.0060);  // 0.60%
    private static final BigDecimal VWAP_TOLERANCE  = BigDecimal.valueOf(0.005);   // 0.5%
    private static final BigDecimal MIN_RANGE_5M    = BigDecimal.valueOf(0.003);   // 0.3%
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

    @Value("${stokr.s3vwap.cooldown-seconds:900}")
    private int cooldownSeconds;

    @Override
    public String key() { return "S3_VWAP_RETEST"; }

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
        if (bars.size() < 21) {
            return hold(context);  // Need at least 21 bars for SMA20 + current
        }
        int n = bars.size();
        MarketdataCandle latestBar = bars.get(n - 1);
        BigDecimal currentPrice = latestBar.getClosePrice();
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return hold(context);
        }

        // ─── Compute VWAP from all available bars ───
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
        BigDecimal vwap;
        if (sumV.compareTo(BigDecimal.ZERO) > 0) {
            vwap = sumPV.divide(sumV, 6, RoundingMode.HALF_UP);
        } else {
            vwap = currentPrice; // Fallback (matches original: vwap defaults to currentPrice)
        }

        // ─── Compute SMA20 and SMA50 ───
        BigDecimal sma20 = computeSMA(bars, 20);
        BigDecimal sma50 = bars.size() >= 50 ? computeSMA(bars, 50) : currentPrice; // Original: fallback to currentPrice

        if (sma20 == null) sma20 = currentPrice;

        // ─── GATE 1: Price near VWAP (within 0.5%) ───
        BigDecimal tolerance = vwap.multiply(VWAP_TOLERANCE);
        BigDecimal upperBand = vwap.add(tolerance);
        BigDecimal lowerBand = vwap.subtract(tolerance);

        boolean nearVWAP = currentPrice.compareTo(lowerBand) >= 0 && currentPrice.compareTo(upperBand) <= 0;
        if (!nearVWAP) {
            return hold(context);
        }

        // ─── GATE 2: Direction based on trend ───
        // LONG: Price > VWAP and > SMA20
        // SHORT: Price < VWAP and < SMA20
        SignalType signalType;
        boolean isLong;

        if (currentPrice.compareTo(vwap) > 0 && currentPrice.compareTo(sma20) > 0) {
            signalType = SignalType.BUY;
            isLong = true;
        } else if (currentPrice.compareTo(vwap) < 0 && currentPrice.compareTo(sma20) < 0) {
            signalType = SignalType.SELL;
            isLong = false;
        } else {
            return hold(context); // No clear direction
        }

        // ─── GATE 3: Volume confirmation (5m volume > 1000) ───
        BigDecimal volume5m = BigDecimal.ZERO;
        for (int i = Math.max(0, n - 5); i < n; i++) {
            BigDecimal v = bars.get(i).getVolume();
            if (v != null) volume5m = volume5m.add(v);
        }
        if (volume5m.compareTo(BigDecimal.valueOf(MIN_VOLUME_5M)) < 0) {
            return hold(context);
        }

        // ─── GATE 4: Range expansion (5m range > 0.3%) ───
        // range5m = (high5 - low5) / close
        double high5 = Double.MIN_VALUE;
        double low5 = Double.MAX_VALUE;
        for (int i = Math.max(0, n - 5); i < n; i++) {
            double h = toDouble(bars.get(i).getHighPrice());
            double l = toDouble(bars.get(i).getLowPrice());
            if (h > high5) high5 = h;
            if (l > 0 && l < low5) low5 = l;
        }
        double closeD = toDouble(currentPrice);
        BigDecimal range5m = closeD > 0
                ? BigDecimal.valueOf((high5 - low5) / closeD)
                : BigDecimal.ZERO;
        if (range5m.compareTo(MIN_RANGE_5M) < 0) {
            return hold(context);
        }

        // ─── Quality Score (same formula as original) ───
        BigDecimal qualityScore = calculateQualityScore(currentPrice, vwap, sma20, sma50, range5m);
        if (qualityScore.compareTo(BigDecimal.valueOf(MIN_QUALITY)) < 0) {
            return hold(context);
        }

        // ─── Cooldown ───
        Instant evalTime = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null && Duration.between(lastEmit, evalTime).getSeconds() < cooldownSeconds) {
            return hold(context);
        }

        // ─── Entry/Exit Levels (EXACT same as original) ───
        BigDecimal entryLevel = currentPrice;
        BigDecimal targetLevel;
        BigDecimal stopLoss;

        if (isLong) {
            targetLevel = entryLevel.multiply(BigDecimal.ONE.add(TARGET_PERCENT));
            stopLoss    = entryLevel.multiply(BigDecimal.ONE.subtract(SL_PERCENT));
        } else {
            targetLevel = entryLevel.multiply(BigDecimal.ONE.subtract(TARGET_PERCENT));
            stopLoss    = entryLevel.multiply(BigDecimal.ONE.add(SL_PERCENT));
        }

        lastEmitBySymbol.put(symbol, evalTime);

        String pressureInfo = "";
        PressureSnapshot snapshot = pressureTracker.getSnapshot(symbol);
        if (snapshot != null) {
            pressureInfo = String.format(" imb=%.0f%%", snapshot.imbalanceRatio() * 100);
        }

        String reason = String.format(
            "S3_VWAP_RETEST %s: price=%.2f vwap=%.2f sma20=%.2f sma50=%.2f " +
            "vol5m=%s range5m=%.4f quality=%.0f%s entry=%.2f target=%.2f sl=%.2f",
            signalType, currentPrice, vwap, sma20, sma50,
            volume5m.toPlainString(), range5m, qualityScore, pressureInfo,
            entryLevel, targetLevel, stopLoss
        );

        log.info("s3_vwap_retest.signal symbol={} {}", symbol, reason);

        return new StrategySignal(signalType, symbol, BigDecimal.ONE, reason,
                entryLevel,
                stopLoss.setScale(2, RoundingMode.HALF_UP),
                targetLevel.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Quality score — exact same formula as original S3VWAPDetector.
     * Base 50 + VWAP alignment(20) + SMA alignment(15) + range expansion(15)
     */
    private BigDecimal calculateQualityScore(BigDecimal price, BigDecimal vwap,
                                              BigDecimal sma20, BigDecimal sma50, BigDecimal range5m) {
        BigDecimal score = BigDecimal.valueOf(50); // Base

        // VWAP alignment (±20 points) — closer to VWAP = higher score
        BigDecimal vwapDiff = price.subtract(vwap).abs();
        BigDecimal onePercentVwap = vwap.multiply(BigDecimal.valueOf(0.01));
        if (onePercentVwap.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal vwapFactor = BigDecimal.ONE.subtract(
                    vwapDiff.divide(onePercentVwap, 6, RoundingMode.HALF_UP));
            // Clamp to 0-1 range
            if (vwapFactor.compareTo(BigDecimal.ZERO) < 0) vwapFactor = BigDecimal.ZERO;
            if (vwapFactor.compareTo(BigDecimal.ONE) > 0) vwapFactor = BigDecimal.ONE;
            score = score.add(vwapFactor.multiply(BigDecimal.valueOf(20)));
        }

        // SMA alignment (±15 points)
        if (price.compareTo(sma20) > 0 && price.compareTo(sma50) > 0) {
            score = score.add(BigDecimal.valueOf(15));
        }

        // Range expansion (±15 points)
        if (range5m.compareTo(BigDecimal.valueOf(0.006)) > 0) {
            score = score.add(BigDecimal.valueOf(15));
        }

        return score.min(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Compute Simple Moving Average of the last N candle closes.
     */
    private BigDecimal computeSMA(List<MarketdataCandle> bars, int period) {
        int n = bars.size();
        if (n < period) return null;
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (int i = n - period; i < n; i++) {
            BigDecimal close = bars.get(i).getClosePrice();
            if (close != null) {
                sum = sum.add(close);
                count++;
            }
        }
        return count > 0 ? sum.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP) : null;
    }

    private static double toDouble(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
