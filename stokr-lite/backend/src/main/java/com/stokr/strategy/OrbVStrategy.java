package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Opening Range Breakout (ORB) — most battle-tested intraday strategy globally.
 *
 * Logic:
 *   First 15 min of trading (9:15-9:30 IST) define the range (high + low).
 *   Entry when close breaks ABOVE range high with strong volume (first breakout only).
 *   SL: below range low — structural invalidation.
 *   Target: range high + 1x range size (ORB extension).
 *   Trailing SL also applied in the backtest exit simulator.
 *
 * Per-day ORB levels are pre-computed by BacktestController and passed via extras:
 *   orbHigh, orbLow, orbRange, dayOpen, istHour, istMinute, chartinkOk
 */
@Slf4j
@Component
public class OrbVStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "ORB_V";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 20) return null;

        // Per-day ORB levels — pre-computed by simulator, not from window start
        BigDecimal orbHigh  = context.extra("orbHigh",  BigDecimal.class);
        BigDecimal orbLow   = context.extra("orbLow",   BigDecimal.class);
        BigDecimal orbRange = context.extra("orbRange",  BigDecimal.class);
        if (orbHigh == null || orbLow == null || orbRange == null) return null;
        if (orbRange.compareTo(BigDecimal.valueOf(0.01)) < 0) return null;

        // Time gate: 9:30–10:30 IST only (first hour breakouts are strongest)
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 9 * 60 + 30 || min > 10 * 60 + 30) return null;
        }

        // Chartink in-house scan filter
        Boolean chartinkOk = context.extra("chartinkOk", Boolean.class);
        if (Boolean.FALSE.equals(chartinkOk)) return null;

        Candle latest = context.getLatestCandle();
        Candle prev   = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        BigDecimal close = latest.close();
        BigDecimal dayOpen = context.extra("dayOpen", BigDecimal.class);

        // Meaningful ORB range: at least 0.5% of orbHigh
        if (orbRange.divide(orbHigh, 6, RoundingMode.HALF_UP).compareTo(BigDecimal.valueOf(0.005)) < 0) return null;

        boolean isLong = close.compareTo(orbHigh) > 0;
        boolean isShort = close.compareTo(orbLow) < 0;
        if (!isLong && !isShort) return null;

        // Don't chase: entry within 0.8% of the range boundary
        if (isLong && close.compareTo(orbHigh.multiply(BigDecimal.valueOf(1.008))) > 0) return null;
        if (isShort && close.compareTo(orbLow.multiply(BigDecimal.valueOf(0.992))) < 0) return null;

        // Trend alignment: long needs price above open, short below open
        if (isLong && dayOpen != null && close.compareTo(dayOpen) <= 0) return null;
        if (isShort && dayOpen != null && close.compareTo(dayOpen) >= 0) return null;

        // Candle direction must match breakout direction
        if (isLong && close.compareTo(latest.open()) <= 0) return null;
        if (isShort && close.compareTo(latest.open()) >= 0) return null;

        // Strong body: at least 70% of range
        BigDecimal candleRange = latest.high().subtract(latest.low());
        double bodyPct = 0;
        if (candleRange.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal body = close.subtract(latest.open()).abs();
            bodyPct = body.divide(candleRange, 4, RoundingMode.HALF_UP).doubleValue();
        }
        if (bodyPct < 0.70) return null;

        // Volume ≥ 3× 10-period average (prior candles only)
        long volSum = 0;
        int volLen = Math.min(10, n - 1);
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;
        if (avgVol == 0 || latest.volume() < avgVol * 3.0) return null;

        // First breakout only: previous candle still inside range
        boolean firstBreakout = isLong ? prev.close().compareTo(orbHigh) <= 0 : prev.close().compareTo(orbLow) >= 0;
        if (!firstBreakout) return null;

        // SL: 0.5% from entry
        BigDecimal sl, target;
        if (isLong) {
            sl = close.multiply(BigDecimal.valueOf(0.995)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal risk = close.subtract(sl);
            target = close.add(risk.multiply(BigDecimal.valueOf(2.0))).setScale(2, RoundingMode.HALF_UP);
        } else {
            sl = close.multiply(BigDecimal.valueOf(1.005)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal risk = sl.subtract(close);
            target = close.subtract(risk.multiply(BigDecimal.valueOf(2.0))).setScale(2, RoundingMode.HALF_UP);
        }

        if (target.compareTo(close) <= 0 || sl.compareTo(close) >= 0) return null;

        double rrRatio = isLong
            ? target.subtract(close).doubleValue() / close.subtract(sl).doubleValue()
            : close.subtract(target).doubleValue() / sl.subtract(close).doubleValue();
        if (rrRatio < 1.0) return null;

        Signal.Side side = isLong ? Signal.Side.BUY : Signal.Side.SELL;
        return new Signal(
            context.symbol(), side, close, sl, target,
            0.78,
            (isLong ? "ORB Long" : "ORB Short") + " @" + close.setScale(2, RoundingMode.HALF_UP)
                + " orb=[" + orbLow.setScale(2, RoundingMode.HALF_UP)
                + "-" + orbHigh.setScale(2, RoundingMode.HALF_UP) + "]"
                + " tgt=" + target
                + " rr=" + String.format("%.1f", rrRatio)
                + " vol=" + String.format("%.1fx", (double) latest.volume() / avgVol),
            1.0, 0.5);
    }
}
