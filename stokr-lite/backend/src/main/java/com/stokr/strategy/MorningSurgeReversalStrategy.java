package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Morning Surge Reversal Strategy — ORB false breakout/breakdown reversal.
 *
 * SHORT setup (failed upside breakout):
 *   - Price briefly went above orbHigh then closed back below (failed breakout)
 *   - OR price breaks directly below orbLow (momentum breakdown)
 *   - Strong bearish body (>= 70%), volume >= 2.5x avg
 *   - SL: above orbHigh, Target: orbLow - 1.5x orbRange
 *
 * LONG setup (failed downside breakdown — mirror):
 *   - Price briefly went below orbLow then closed back above (failed breakdown)
 *   - OR price breaks directly above orbHigh (momentum breakout)
 *   - Strong bullish body (>= 70%), volume >= 2.5x avg
 *   - SL: below orbLow, Target: orbHigh + 1.5x orbRange
 */
@Slf4j
@Component
public class MorningSurgeReversalStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "MORNING_SURGE_REVERSAL";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 20) return null;

        BigDecimal orbHigh  = context.extra("orbHigh",  BigDecimal.class);
        BigDecimal orbLow   = context.extra("orbLow",   BigDecimal.class);
        BigDecimal orbRange = context.extra("orbRange",  BigDecimal.class);
        if (orbHigh == null || orbLow == null || orbRange == null) return null;
        // Minimum 0.3% ORB range — skip flat/illiquid days
        Candle tmpClose = candles.get(n - 1);
        if (orbRange.doubleValue() / tmpClose.close().doubleValue() < 0.003) return null;

        // Tight morning window only: 9:30–10:30 IST
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 9 * 60 + 30 || min > 10 * 60 + 30) return null;
        }

        Candle latest = context.getLatestCandle();
        Candle prev   = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        BigDecimal close = latest.close();

        // Volume >= 2.5x 10-period average
        long volSum = 0;
        int volLen = Math.min(10, n - 1);
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;
        if (avgVol == 0 || latest.volume() < avgVol * 2.5) return null;

        // Strong body (>= 70% of candle range)
        BigDecimal range = latest.high().subtract(latest.low());
        double bodyPct = 0;
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal body = latest.open().subtract(close).abs();
            bodyPct = body.divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        }
        if (bodyPct < 0.70) return null;

        // ─── SHORT: failed upside breakout OR direct breakdown ───────────────
        boolean failedBreakout = close.compareTo(orbHigh) <= 0
            && prev.close().compareTo(orbHigh) > 0;
        boolean directBreakdown = close.compareTo(orbLow) <= 0;

        if ((failedBreakout || directBreakdown) && close.compareTo(latest.open()) < 0) {
            BigDecimal sl = orbHigh.multiply(BigDecimal.valueOf(1.001)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal target = orbLow.subtract(orbRange.multiply(BigDecimal.valueOf(1.5))).setScale(2, RoundingMode.HALF_UP);

            if (target.compareTo(close) < 0 && sl.compareTo(close) > 0) {
                double risk = sl.subtract(close).doubleValue();
                double reward = close.subtract(target).doubleValue();
                double rrRatio = risk > 0 ? reward / risk : 0;
                if (rrRatio >= 1.0) {
                    double pctDist = risk / close.doubleValue() * 100.0;
                    double trailTrigger = Math.max(0.6, pctDist * 1.2);
                    double trailDistance = Math.max(0.3, pctDist * 0.6);
                    String label = failedBreakout ? "FAILED_BREAKOUT" : "BREAKDOWN";
                    return new Signal(
                        context.symbol(), Signal.Side.SELL, close, sl, target, 0.70,
                        "MSR_SHORT " + label + " @" + close.setScale(2, RoundingMode.HALF_UP)
                            + " orb=[" + orbLow.setScale(2, RoundingMode.HALF_UP) + "-" + orbHigh.setScale(2, RoundingMode.HALF_UP) + "]"
                            + " tgt=" + target + " rr=" + String.format("%.1f", rrRatio)
                            + " vol=" + String.format("%.1fx", (double) latest.volume() / avgVol),
                        trailTrigger, trailDistance);
                }
            }
        }

        return null;
    }
}
