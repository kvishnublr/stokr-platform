package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Morning Surge Strategy — high-conviction ORB variation for explosive gap days.
 *
 * Difference from ORB:
 *   - Tighter time window: 9:30–10:30 IST only (pure morning momentum)
 *   - Higher volume bar: ≥ 3× 10-period average (vs 1.5× for ORB)
 *   - Stronger candle: body ≥ 60% of range (decisive move, not a wick)
 *   - Larger target: orbHigh + 2× orbRange (vs 1× for ORB)
 *   - No-chase: entry within 1% of ORB high (tighter than ORB's 1.5%)
 *
 * Rationale: Stocks that break out early with extreme volume on surge days
 * tend to run further than a regular ORB breakout.
 */
@Slf4j
@Component
public class MorningSurgeStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "MORNING_SURGE"; }

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

        // 1. Breakout above ORB high
        if (close.compareTo(orbHigh) <= 0) return null;

        // 2. First breakout only
        if (prev.close().compareTo(orbHigh) > 0) return null;

        // 3. No chase — tighter than ORB (1% vs 1.5%)
        if (close.compareTo(orbHigh.multiply(BigDecimal.valueOf(1.01))) > 0) return null;

        // 4. Trend day: close > day open
        BigDecimal dayOpen = context.extra("dayOpen", BigDecimal.class);
        if (dayOpen != null && close.compareTo(dayOpen) <= 0) return null;

        // 5. Strong bullish candle: body ≥ 60% of range (decisive, not a wick)
        if (close.compareTo(latest.open()) <= 0) return null;
        BigDecimal range = latest.high().subtract(latest.low());
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal bodyPct = close.subtract(latest.open())
                .divide(range, 4, RoundingMode.HALF_UP);
            if (bodyPct.doubleValue() < 0.60) return null;
        }

        // 6. HIGH volume: ≥ 3× 10-period average (surge, not just a tick)
        long volSum = 0;
        int volLen = Math.min(10, n);
        for (int k = n - volLen; k < n; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;
        if (avgVol == 0 || latest.volume() < avgVol * 3) return null;

        // SL: below ORB low
        BigDecimal sl = orbLow.multiply(BigDecimal.valueOf(0.999)).setScale(2, RoundingMode.HALF_UP);

        // Target: 2× ORB extension (larger than standard ORB)
        BigDecimal target = orbHigh.add(orbRange.multiply(BigDecimal.valueOf(2)))
            .setScale(2, RoundingMode.HALF_UP);

        if (target.compareTo(close) <= 0 || sl.compareTo(close) >= 0) return null;

        double rrRatio = target.subtract(close).doubleValue() / close.subtract(sl).doubleValue();
        if (rrRatio < 1.0) return null; // Minimum 1:1 R:R

        return new Signal(
            context.symbol(), Signal.Side.BUY, close, sl, target,
            0.72,
            "SURGE @" + close.setScale(2, RoundingMode.HALF_UP)
                + " orb=[" + orbLow.setScale(2, RoundingMode.HALF_UP)
                + "-" + orbHigh.setScale(2, RoundingMode.HALF_UP) + "]"
                + " tgt=" + target
                + " rr=" + String.format("%.1f", rrRatio)
                + " vol=" + String.format("%.1fx", (double) latest.volume() / avgVol));
    }
}
