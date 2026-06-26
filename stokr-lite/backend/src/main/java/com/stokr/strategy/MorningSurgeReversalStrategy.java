package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Morning Surge Reversal Strategy — short-side counterpart to MorningSurgeStrategy.
 *
 * Detects failed breakouts and bearish reversals after the morning surge:
 *   - Price initially broke above ORB high but reversed back below it (failed breakout)
 *   - OR: price breaks below ORB low with strong volume (breakdown)
 *   - Requires bearish candle with ≥ 70% body, volume ≥ 2.5× average, time 9:30–10:30
 *
 * Target: ORB low − 1.5× orbRange | SL: above ORB high | Min R:R: 1:1
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
        if (orbRange.compareTo(BigDecimal.valueOf(0.01)) < 0) return null;

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

        // Type A — Failed breakout: price briefly went above ORB high and now back below
        boolean failedBreakout = close.compareTo(orbHigh) <= 0
            && prev.close().compareTo(orbHigh) > 0;

        // Type B — Direct breakdown: price breaks below ORB low
        boolean directBreakdown = close.compareTo(orbLow) <= 0;

        if (!failedBreakout && !directBreakdown) return null;

        // Bearish candle: close < open
        if (close.compareTo(latest.open()) >= 0) return null;

        // Strong body ≥ 70% of range (decisive rejection)
        BigDecimal range = latest.high().subtract(latest.low());
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal bodyPct = latest.open().subtract(close)
                .divide(range, 4, RoundingMode.HALF_UP);
            if (bodyPct.doubleValue() < 0.70) return null;
        }

        // Volume ≥ 2× 10-period average (prior candles only, not latest)
        long volSum = 0;
        int volLen = Math.min(10, n - 1);
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;
        if (avgVol == 0 || latest.volume() < avgVol * 2.5) return null;

        // SL: above ORB high (structural)
        BigDecimal sl = orbHigh.multiply(BigDecimal.valueOf(1.001)).setScale(2, RoundingMode.HALF_UP);
        // Target: orbLow − 1.5× orbRange
        BigDecimal target = orbLow.subtract(orbRange.multiply(BigDecimal.valueOf(1.5)))
            .setScale(2, RoundingMode.HALF_UP);

        if (target.compareTo(close) >= 0 || sl.compareTo(close) <= 0) return null;

        // R:R = reward / risk — must be > 1.0 (good trades)
        double risk = sl.subtract(close).doubleValue();
        double reward = close.subtract(target).doubleValue();
        double rrRatio = risk > 0 ? reward / risk : 0;
        if (rrRatio < 1.0) return null;

        // Trailing stop proportional to SL distance
        double pctDist = risk / close.doubleValue() * 100.0;
        double trailTrigger = Math.max(0.6, pctDist * 1.2);
        double trailDistance = Math.max(0.3, pctDist * 0.6);

        String label = failedBreakout ? "FAILED_BREAKOUT" : "BREAKDOWN";
        return new Signal(
            context.symbol(), Signal.Side.SELL, close, sl, target,
            0.70,
            "MORNING_REVERSAL " + label + " @" + close.setScale(2, RoundingMode.HALF_UP)
                + " orb=[" + orbLow.setScale(2, RoundingMode.HALF_UP)
                + "-" + orbHigh.setScale(2, RoundingMode.HALF_UP) + "]"
                + " tgt=" + target
                + " rr=" + String.format("%.1f", rrRatio)
                + " vol=" + String.format("%.1fx", (double) latest.volume() / avgVol),
            trailTrigger, trailDistance);
    }
}
