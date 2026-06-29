package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * ORB Retest Long — buy the FIRST pullback to orbHigh after a confirmed breakout.
 *
 * This is the professional ORB entry:
 *   1. A prior candle (within last 5–20 candles) broke cleanly above orbHigh (≥0.3% above)
 *   2. Price pulled back: at least one recent candle's LOW touched orbHigh ± 0.25%
 *   3. Current candle bounces: closes above orbHigh + 0.15% (bullish off support)
 *   4. Current candle is bullish, body ≥ 55%
 *   5. Volume ≥ 1.5x avg on bounce candle
 *
 * Why high WR: We only trade AFTER a breakout is confirmed AND price retests the level.
 * False breakouts don't retest — they collapse below. Only true breakouts hold and retest.
 *
 * SL: just below orbHigh (0.2% buffer) — if retest fails, breakout was fake
 * Target: 2x risk (fixed) — clean R:R, higher WR than trailing
 * Window: 9:35–12:30 IST (need time for breakout + retest cycle)
 */
@Slf4j
@Component
public class OrbRetestLongStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "ORB_RETEST_LONG"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 25) return null;

        // Time window: 9:35–12:30 IST (need breakout candle + retest candle)
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 9 * 60 + 35)  return null;
            if (min > 12 * 60 + 30) return null;
        }

        BigDecimal orbHigh  = context.extra("orbHigh",  BigDecimal.class);
        BigDecimal orbLow   = context.extra("orbLow",   BigDecimal.class);
        BigDecimal orbRange = context.extra("orbRange", BigDecimal.class);
        if (orbHigh == null || orbLow == null || orbRange == null) return null;

        Candle curr = candles.get(n - 1);
        BigDecimal close = curr.close();
        double orbH = orbHigh.doubleValue();

        // ORB range quality: 0.3%–2.5% of price
        double orbRangePct = orbRange.doubleValue() / close.doubleValue();
        if (orbRangePct < 0.003 || orbRangePct > 0.025) return null;

        // Current candle: bullish bounce above orbHigh
        if (close.compareTo(curr.open()) <= 0) return null;
        double closePct = (close.doubleValue() - orbH) / orbH;
        if (closePct < 0.0015) return null;  // close at least 0.15% above orbHigh

        // Body >= 55%
        BigDecimal range = curr.high().subtract(curr.low());
        if (range.compareTo(BigDecimal.ZERO) <= 0) return null;
        double bodyPct = close.subtract(curr.open()).divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        if (bodyPct < 0.55) return null;

        // Two-pass scan within lookback window:
        // Pass 1 (oldest→newest): find FIRST candle that broke above orbHigh cleanly
        // Pass 2: find a candle AFTER breakout whose low touched back near orbHigh (retest)
        int lookback = Math.min(25, n - 2);
        int startIdx = n - 1 - lookback;

        // Pass 1: find breakout (oldest first)
        int breakoutIdx = -1;
        for (int k = startIdx; k < n - 1; k++) {
            Candle c = candles.get(k);
            if (c.close().doubleValue() >= orbH * 1.003 && c.close().compareTo(c.open()) > 0) {
                if (k > 0 && candles.get(k - 1).close().compareTo(orbHigh) <= 0) {
                    breakoutIdx = k;
                    break;
                }
            }
        }
        if (breakoutIdx < 0) return null;

        // Pass 2: find retest after breakout (between breakoutIdx+1 and n-2)
        boolean foundRetest = false;
        for (int k = breakoutIdx + 1; k <= n - 2; k++) {
            double cLow = candles.get(k).low().doubleValue();
            // Low came back within 0.4% above orbHigh — genuine retest zone
            if (cLow <= orbH * 1.004 && cLow >= orbH * 0.996) {
                foundRetest = true;
                break;
            }
        }
        if (!foundRetest) return null;

        // Volume: current candle >= 1.5x 20-bar avg
        int volLen = Math.min(20, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;
        double volMult = avgVol > 0 ? (double) curr.volume() / avgVol : 0;
        if (volMult < 1.5) return null;

        // SL just below orbHigh
        BigDecimal sl = orbHigh.multiply(BigDecimal.valueOf(0.998)).setScale(2, RoundingMode.HALF_UP);
        double risk = close.doubleValue() - sl.doubleValue();
        if (risk <= 0) return null;

        double riskPct = risk / close.doubleValue();
        if (riskPct > 0.015) return null;  // don't trade if too far from orbHigh
        if (riskPct < 0.002) return null;

        // Fixed 2:1 target
        BigDecimal target = close.add(BigDecimal.valueOf(2.0 * risk)).setScale(2, RoundingMode.HALF_UP);

        log.debug("ORL: {} orbH={} close={}% above vol={}x body={}% risk={}%",
            context.symbol(), orbHigh.setScale(2, RoundingMode.HALF_UP),
            String.format("%.2f", closePct * 100), String.format("%.1f", volMult),
            String.format("%.0f", bodyPct * 100), String.format("%.2f", riskPct * 100));

        return new Signal(
            context.symbol(), Signal.Side.BUY, close, sl, target,
            0.70,
            "ORL @" + close.setScale(2, RoundingMode.HALF_UP)
                + " retest orbH=" + orbHigh.setScale(2, RoundingMode.HALF_UP)
                + " vol=" + String.format("%.1fx", volMult)
                + " sl=" + sl + " tgt=" + target,
            10.0, 0.0);  // trail trigger at 10% = effectively never fires; fixed 2:1 target governs
    }
}
