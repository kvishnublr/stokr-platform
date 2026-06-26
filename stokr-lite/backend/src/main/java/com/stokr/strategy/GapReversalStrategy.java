package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Gap Reversal Strategy — large-cap stocks that gap up 1%+ at open then fail to hold.
 *
 * Setup (SHORT — gap-up reversal):
 *   - Today's open > prevDayClose × 1.010 (gap up of >= 1%)
 *   - Gap <= 3% (not a runaway gap, likely to fill)
 *   - Current candle: bearish, strong body >= 60%, volume >= 2.5× avg
 *   - Price still above prevDayClose (not yet filled the gap)
 *   - SL: today's orbHigh + 0.2% (above the morning high)
 *   - Target: prevDayClose (full gap fill)
 *   - Window: 9:30–11:00 IST (before midday trend solidifies)
 */
@Slf4j
@Component
public class GapReversalStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "GAP_REVERSAL"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 20) return null;

        BigDecimal prevDayClose = context.extra("prevDayClose", BigDecimal.class);
        if (prevDayClose == null || prevDayClose.compareTo(BigDecimal.ZERO) == 0) return null;

        BigDecimal dayOpen = context.extra("dayOpen", BigDecimal.class);
        if (dayOpen == null) return null;

        // Gap up: today's open must be 1-3% above previous day's close
        double gapPct = dayOpen.subtract(prevDayClose).doubleValue() / prevDayClose.doubleValue() * 100.0;
        if (gapPct < 1.0 || gapPct > 3.0) return null;

        // Window: 9:30–11:00 IST
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 9 * 60 + 30 || min > 11 * 60 + 0) return null;
        }

        Candle latest = context.getLatestCandle();
        Candle prev   = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        BigDecimal close = latest.close();

        // Price must still be above prevDayClose (gap not yet filled)
        if (close.compareTo(prevDayClose) <= 0) return null;

        // Must be bearish candle
        if (close.compareTo(latest.open()) >= 0) return null;

        // Strong body >= 60%
        BigDecimal range = latest.high().subtract(latest.low());
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            double bodyPct = latest.open().subtract(close).divide(range, 4, RoundingMode.HALF_UP).doubleValue();
            if (bodyPct < 0.60) return null;
        }

        // Volume >= 2.5× 10-bar avg
        int volLen = Math.min(10, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;
        if (avgVol == 0 || latest.volume() < avgVol * 2.5) return null;

        // SL: use orbHigh + 0.2% (morning high invalidation)
        BigDecimal orbHigh = context.extra("orbHigh", BigDecimal.class);
        BigDecimal sl = orbHigh != null
            ? orbHigh.multiply(BigDecimal.valueOf(1.002)).setScale(2, RoundingMode.HALF_UP)
            : close.multiply(BigDecimal.valueOf(1.015)).setScale(2, RoundingMode.HALF_UP);

        // Target: prevDayClose (gap fill)
        BigDecimal target = prevDayClose.setScale(2, RoundingMode.HALF_UP);

        if (target.compareTo(close) >= 0) return null;
        if (sl.compareTo(close) <= 0) return null;

        double risk   = sl.subtract(close).doubleValue();
        double reward = close.subtract(target).doubleValue();
        double rrRatio = risk > 0 ? reward / risk : 0;
        if (rrRatio < 1.0) return null;

        double pctDist    = risk / close.doubleValue() * 100.0;
        double trailTrigger = Math.max(0.5, pctDist * 1.0);
        double trailDistance = Math.max(0.3, pctDist * 0.5);

        return new Signal(
            context.symbol(), Signal.Side.SELL, close, sl, target, 0.72,
            "GAP_REV SHORT gap=" + String.format("%.1f%%", gapPct)
                + " @" + close.setScale(2, RoundingMode.HALF_UP)
                + " tgt=" + target
                + " rr=" + String.format("%.1f", rrRatio)
                + " vol=" + String.format("%.1fx", (double) latest.volume() / avgVol),
            trailTrigger, trailDistance);
    }
}
