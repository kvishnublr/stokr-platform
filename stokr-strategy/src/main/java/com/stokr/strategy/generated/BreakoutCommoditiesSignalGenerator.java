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
import java.time.ZonedDateTime;
import java.util.List;

/**
 * 10-minute breakout strategy for MCX commodities (Gold, Silver, Crude Oil, Natural Gas etc.).
 *
 * Logic:
 *   1. Load the last {@code lookbackBars + 5} completed 10m bars.
 *   2. Compute the N-bar prior range (high / low) excluding the current bar.
 *   3. Compute ATR(14) and RSI(14) over the candle series.
 *   4. BUY  when close > priorHigh * (1 + minBreakoutPct) AND rsi > 55 AND atr/close > minAtrRatio
 *   5. SELL when close < priorLow  * (1 - minBreakoutPct) AND rsi < 45 AND atr/close > minAtrRatio
 *   6. Gate on MCX session window (09:00–23:30 IST); skip outside hours.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "BREAKOUT_COMMODITIES",
    assetClass   = "COMMODITY",
    segment      = "MCX",
    exchange     = "NSE",
    timeframe    = "10m"
)
public class BreakoutCommoditiesSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "10m";
    private static final int BARS_TO_FETCH = 120;

    private final MarketDataQueryService marketDataQueryService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.commodities10m.session.start:09:00}")
    private LocalTime sessionStart;

    @Value("${stokr.commodities10m.session.end:23:30}")
    private LocalTime sessionEnd;

    @Value("${stokr.commodities10m.lookback-bars:20}")
    private int lookbackBars;

    @Value("${stokr.commodities10m.atr-period:14}")
    private int atrPeriod;

    @Value("${stokr.commodities10m.rsi-period:14}")
    private int rsiPeriod;

    @Value("${stokr.commodities10m.min-breakout-pct:0.0020}")
    private double minBreakoutPct;

    @Value("${stokr.commodities10m.min-atr-ratio:0.0015}")
    private double minAtrRatio;

    @Override
    public String key() {
        return "BREAKOUT_COMMODITIES";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();

        // ── 1. Session gate ──────────────────────────────────────────────────
        if (context.asOf() != null) {
            ZonedDateTime zdt = context.asOf().atZone(zone);
            LocalTime lt = zdt.toLocalTime();
            if (lt.isBefore(sessionStart) || lt.isAfter(sessionEnd)) {
                return hold(context);
            }
        }

        // ── 2. Load candles ──────────────────────────────────────────────────
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(
                symbol, TIMEFRAME, BARS_TO_FETCH, context.asOf());
        int needed = lookbackBars + atrPeriod + 2;
        if (bars.size() < needed) {
            log.debug("strategy.breakout_commodities.insufficient_bars symbol={} have={} need={}", symbol, bars.size(), needed);
            return hold(context);
        }

        // ── 3. Extract series ────────────────────────────────────────────────
        int n = bars.size();
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

        // ── 4. Current-bar values (last bar is the signal bar) ───────────────
        double currentClose = closes[n - 1];
        if (currentClose <= 0) return hold(context);

        // ── 5. Prior N-bar range (exclude signal bar) ────────────────────────
        int rangeFrom = n - 1 - lookbackBars;
        double[] priorHighs = subarray(highs, rangeFrom, n - 1);
        double[] priorLows  = subarray(lows,  rangeFrom, n - 1);
        double priorHigh = max(priorHighs);
        double priorLow  = min(priorLows);

        // ── 6. ATR(14) ────────────────────────────────────────────────────────
        double atr = computeAtr(highs, lows, closes, atrPeriod);
        double atrRatio = atr / currentClose;
        if (atrRatio < minAtrRatio) {
            log.debug("strategy.breakout_commodities.low_atr symbol={} atrRatio={}", symbol, atrRatio);
            return hold(context);
        }

        // ── 7. RSI(14) ────────────────────────────────────────────────────────
        double rsiValue = rsi(closes, rsiPeriod);

        // ── 8. Breakout thresholds ────────────────────────────────────────────
        double thresholdUp = priorHigh * (1 + minBreakoutPct);
        double thresholdDn = priorLow  * (1 - minBreakoutPct);

        boolean bullish = currentClose > thresholdUp && rsiValue > 55;
        boolean bearish = currentClose < thresholdDn && rsiValue < 45;

        if (!bullish && !bearish) {
            return hold(context);
        }

        SignalType type = bullish ? SignalType.BUY : SignalType.SELL;
        String reason = String.format(
                "10m breakout %s priorRange=[%.2f,%.2f] close=%.2f rsi=%.1f atrRatio=%.4f",
                type.name(), priorLow, priorHigh, currentClose, rsiValue, atrRatio);
        log.debug("strategy.breakout_commodities.signal symbol={} type={} reason={}", symbol, type, reason);
        return new StrategySignal(type, symbol, BigDecimal.ONE, reason);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double computeAtr(double[] highs, double[] lows, double[] closes, int period) {
        int n = closes.length;
        if (n < period + 1) return Double.NaN;
        // True range series
        double[] tr = new double[n - 1];
        for (int i = 1; i < n; i++) {
            double hl  = highs[i] - lows[i];
            double hc  = Math.abs(highs[i] - closes[i - 1]);
            double lc  = Math.abs(lows[i]  - closes[i - 1]);
            tr[i - 1] = Math.max(hl, Math.max(hc, lc));
        }
        // Wilder smoothing
        double atr = 0;
        for (int i = 0; i < period; i++) atr += tr[i];
        atr /= period;
        for (int i = period; i < tr.length; i++) {
            atr = (atr * (period - 1) + tr[i]) / period;
        }
        return atr;
    }

    private static double[] subarray(double[] src, int from, int to) {
        double[] out = new double[to - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    private static double toDouble(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }
}
