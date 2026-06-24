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

        // Time gate: 9:30–13:00 IST only
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 9 * 60 + 30 || min > 13 * 60) return null;
        }

        // Chartink in-house scan filter — if set to false, skip this symbol today
        Boolean chartinkOk = context.extra("chartinkOk", Boolean.class);
        if (Boolean.FALSE.equals(chartinkOk)) return null;

        Candle latest = context.getLatestCandle();
        Candle prev   = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        BigDecimal close = latest.close();

        // 1. ORB breakout: close above range high
        if (close.compareTo(orbHigh) <= 0) return null;

        // 2. Don't chase: entry must be within 1.5% of ORB high
        if (close.compareTo(orbHigh.multiply(BigDecimal.valueOf(1.015))) > 0) return null;

        // 3. Trend day: close > day open
        BigDecimal dayOpen = context.extra("dayOpen", BigDecimal.class);
        if (dayOpen != null && close.compareTo(dayOpen) <= 0) return null;

        // 4. Breakout candle is bullish
        if (close.compareTo(latest.open()) <= 0) return null;

        // 5. Volume ≥ 1.5× 10-period average
        long volSum = 0;
        int volLen = Math.min(10, n);
        for (int k = n - volLen; k < n; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;
        if (avgVol == 0 || latest.volume() < avgVol * 1.5) return null;

        // 6. First breakout only: previous candle was still below/at ORB high
        if (prev.close().compareTo(orbHigh) > 0) return null;

        // SL: just below ORB low (structural)
        BigDecimal sl = orbLow.multiply(BigDecimal.valueOf(0.999)).setScale(2, RoundingMode.HALF_UP);

        // Target: ORB extension = orbHigh + 1× orbRange
        BigDecimal target = orbHigh.add(orbRange).setScale(2, RoundingMode.HALF_UP);

        if (target.compareTo(close) <= 0 || sl.compareTo(close) >= 0) return null;

        double rrRatio = target.subtract(close).doubleValue() / close.subtract(sl).doubleValue();
        if (rrRatio < 0.5) return null;

        return new Signal(
            context.symbol(), Signal.Side.BUY, close, sl, target,
            0.78,
            "ORB @" + close.setScale(2, RoundingMode.HALF_UP)
                + " orb=[" + orbLow.setScale(2, RoundingMode.HALF_UP)
                + "-" + orbHigh.setScale(2, RoundingMode.HALF_UP) + "]"
                + " tgt=" + target
                + " rr=" + String.format("%.1f", rrRatio)
                + " vol=" + String.format("%.1fx", (double) latest.volume() / avgVol));
    }
}
