package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * MCX 10-minute breakout strategy.
 *
 * Logic:
 *   1. Defines a range using the highest high and lowest low of the prior RANGE_PERIOD (20) 10m bars.
 *   2. Fires BUY  when current close breaks above range high + epsilon, RSI > 55, volume > 1.5x avg.
 *   3. Fires SELL when current close breaks below range low  - epsilon, RSI < 45, volume > 1.5x avg.
 *   4. Stop  = entry ± 1.5 × ATR(14).  Target = entry ± 2.5 × ATR(14).  RR ≈ 1 : 1.67.
 *
 * Session: MCX 09:00–23:00 IST (configurable via stokr.breakout.commodities.session.*).
 * All thresholds are @Value-configurable for live tuning without redeployment.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "BREAKOUT_COMMODITIES",
    assetClass   = "COMMODITY",
    segment      = "MCX",
    exchange     = "MCX",
    timeframe    = "10m"
)
public class BreakoutCommoditiesSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME    = "10m";
    private static final int    BARS_FETCH   = 35;  // 35 × 10m = enough for ATR seed + range
    private static final int    RANGE_PERIOD = 20;  // 20-bar range = 200 min prior context
    private static final int    ATR_PERIOD   = 14;
    private static final int    RSI_PERIOD   = 14;
    private static final int    VOL_AVG_BARS = 20;

    private final MarketDataQueryService marketDataQueryService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.breakout.commodities.session.start:09:00}")
    private LocalTime sessionStart;

    @Value("${stokr.breakout.commodities.session.end:23:00}")
    private LocalTime sessionEnd;

    // RSI thresholds — momentum alignment filter
    @Value("${stokr.breakout.commodities.rsi.buy-min:55.0}")
    private double rsiBuyMin;

    @Value("${stokr.breakout.commodities.rsi.sell-max:45.0}")
    private double rsiSellMax;

    // Minimum volume ratio vs 20-bar average required to confirm breakout
    @Value("${stokr.breakout.commodities.vol-ratio-min:1.5}")
    private double volRatioMin;

    // ATR multiples for stop and target
    @Value("${stokr.breakout.commodities.stop-atr-mult:1.5}")
    private double stopAtrMult;

    @Value("${stokr.breakout.commodities.target-atr-mult:2.5}")
    private double targetAtrMult;

    @Override
    public String key() {
        return "BREAKOUT_COMMODITIES";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();

        // ── 1. Session gate ───────────────────────────────────────────────────
        if (context.asOf() != null) {
            LocalTime lt = context.asOf().atZone(zone).toLocalTime();
            if (lt.isBefore(sessionStart) || lt.isAfter(sessionEnd)) {
                return hold(context);
            }
        }

        // ── 2. Load 10m candles ───────────────────────────────────────────────
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(symbol, TIMEFRAME, BARS_FETCH);
        int n = bars.size();
        if (n < ATR_PERIOD + 3) {
            log.debug("strategy.breakout_commodities.insufficient_bars symbol={} have={}", symbol, n);
            return hold(context);
        }

        double[] closes  = new double[n];
        double[] highs   = new double[n];
        double[] lows    = new double[n];
        double[] volumes = new double[n];
        for (int i = 0; i < n; i++) {
            MarketdataCandle c = bars.get(i);
            closes[i]  = toDouble(c.getClosePrice());
            highs[i]   = toDouble(c.getHighPrice());
            lows[i]    = toDouble(c.getLowPrice());
            volumes[i] = toDouble(c.getVolume());
        }

        double curClose = closes[n - 1];
        if (curClose <= 0) return hold(context);

        // ── 3. Range (prior RANGE_PERIOD bars, current bar excluded) ─────────
        int rangeFrom = Math.max(0, n - 1 - RANGE_PERIOD);
        double rangeHigh = Double.NEGATIVE_INFINITY;
        double rangeLow  = Double.POSITIVE_INFINITY;
        for (int i = rangeFrom; i < n - 1; i++) {
            if (highs[i]  > rangeHigh) rangeHigh = highs[i];
            if (lows[i]   < rangeLow)  rangeLow  = lows[i];
        }
        if (rangeHigh <= 0 || rangeLow <= 0 || rangeHigh <= rangeLow) return hold(context);

        // ── 4. Indicators ─────────────────────────────────────────────────────
        double atrVal  = wilderAtr(bars, ATR_PERIOD);
        if (atrVal <= 0) return hold(context);

        double rsiVal  = rsi(closes, RSI_PERIOD);
        double avgVol  = rollingAvg(volumes, n - 1, Math.min(VOL_AVG_BARS, n - 1));
        double volRatio = avgVol > 0 ? volumes[n - 1] / avgVol : 0.0;

        // ── 5. Breakout detection ─────────────────────────────────────────────
        // Epsilon avoids false breakouts on noise — max(0.05% of price, 10% of ATR)
        double epsilon   = Math.max(curClose * 0.0005, atrVal * 0.1);
        boolean bullBreak = curClose > rangeHigh + epsilon;
        boolean bearBreak = curClose < rangeLow  - epsilon;

        if (!bullBreak && !bearBreak) return hold(context);

        // ── 6. Confirmation filters ───────────────────────────────────────────
        if (volRatio < volRatioMin) {
            log.debug("strategy.breakout_commodities.vol_fail symbol={} ratio={:.2f} min={}", symbol, volRatio, volRatioMin);
            return hold(context);
        }

        if (bullBreak && rsiVal < rsiBuyMin) {
            log.debug("strategy.breakout_commodities.rsi_fail symbol={} rsi={:.1f} need>={}", symbol, rsiVal, rsiBuyMin);
            return hold(context);
        }
        if (bearBreak && rsiVal > rsiSellMax) {
            log.debug("strategy.breakout_commodities.rsi_fail symbol={} rsi={:.1f} need<={}", symbol, rsiVal, rsiSellMax);
            return hold(context);
        }

        // ── 7. Signal ─────────────────────────────────────────────────────────
        double stop, target;
        SignalType type;
        String reason;

        if (bullBreak) {
            stop   = curClose - stopAtrMult   * atrVal;
            target = curClose + targetAtrMult * atrVal;
            type   = SignalType.BUY;
            reason = String.format(
                    "MCX 10m breakout UP rangeHigh=%.2f close=%.2f atr=%.2f rsi=%.1f vol=%.1fx stop=%.2f target=%.2f",
                    rangeHigh, curClose, atrVal, rsiVal, volRatio, stop, target);
        } else {
            stop   = curClose + stopAtrMult   * atrVal;
            target = curClose - targetAtrMult * atrVal;
            type   = SignalType.SELL;
            reason = String.format(
                    "MCX 10m breakout DOWN rangeLow=%.2f close=%.2f atr=%.2f rsi=%.1f vol=%.1fx stop=%.2f target=%.2f",
                    rangeLow, curClose, atrVal, rsiVal, volRatio, stop, target);
        }

        log.info("strategy.breakout_commodities.signal symbol={} type={} reason={}", symbol, type, reason);
        return new StrategySignal(type, symbol, BigDecimal.ONE, reason);
    }

    // ── Wilder ATR ────────────────────────────────────────────────────────────

    private static double wilderAtr(List<MarketdataCandle> bars, int period) {
        int n = bars.size();
        if (n < period + 1) return 0.0;
        int seed = n - period - 1;
        double atrVal = 0.0;
        for (int i = seed + 1; i <= seed + period; i++) {
            atrVal += trueRange(bars.get(i), bars.get(i - 1));
        }
        atrVal /= period;
        for (int i = seed + period + 1; i < n; i++) {
            atrVal = (atrVal * (period - 1) + trueRange(bars.get(i), bars.get(i - 1))) / period;
        }
        return atrVal;
    }

    private static double trueRange(MarketdataCandle cur, MarketdataCandle prev) {
        double hl = Math.abs(toDouble(cur.getHighPrice()) - toDouble(cur.getLowPrice()));
        double hc = Math.abs(toDouble(cur.getHighPrice()) - toDouble(prev.getClosePrice()));
        double lc = Math.abs(toDouble(cur.getLowPrice())  - toDouble(prev.getClosePrice()));
        return Math.max(hl, Math.max(hc, lc));
    }

    private static double rollingAvg(double[] arr, int endExclusive, int period) {
        if (endExclusive < period || period <= 0) return 0.0;
        double sum = 0;
        for (int i = endExclusive - period; i < endExclusive; i++) sum += arr[i];
        return sum / period;
    }

    private static double toDouble(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }
}
