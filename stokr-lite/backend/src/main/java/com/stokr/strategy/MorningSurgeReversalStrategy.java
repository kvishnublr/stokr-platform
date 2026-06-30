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
            // Active windows: 9:30–9:44 (early surge) and 10:00–10:30 (confirmed reversal)
            // 9:45–9:59 is noise — skip it (tested: -Rs 123/trade net in that window)
            if (min < 9 * 60 + 30) return null;
            if (min >= 9 * 60 + 45 && min < 10 * 60 + 0) return null;
            if (min > 10 * 60 + 30) return null;
        }

        Candle latest = context.getLatestCandle();
        Candle prev   = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        BigDecimal close = latest.close();

        // Gap filter: skip stocks that gapped up >1.5% at open (bullish momentum day — shorts fail)
        BigDecimal prevDayClose = context.extra("prevDayClose", BigDecimal.class);
        BigDecimal dayOpen = context.extra("dayOpen", BigDecimal.class);
        if (prevDayClose != null && dayOpen != null && prevDayClose.compareTo(BigDecimal.ZERO) > 0) {
            double gapUp = dayOpen.subtract(prevDayClose).doubleValue() / prevDayClose.doubleValue();
            if (gapUp > 0.015) return null;  // skip >1.5% gap-up days
        }

        // Volume >= 2.5x 10-period average
        long volSum = 0;
        int volLen = Math.min(10, n - 1);
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
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
            // directBreakdown has stronger momentum — give it a bigger target (2.5×)
            // failedBreakout is a weaker setup — use conservative 1.8× to still collect
            double targetMult = directBreakdown ? 2.5 : 1.8;
            BigDecimal target = orbLow.subtract(orbRange.multiply(BigDecimal.valueOf(targetMult))).setScale(2, RoundingMode.HALF_UP);

            if (target.compareTo(close) < 0 && sl.compareTo(close) > 0) {
                double risk = sl.subtract(close).doubleValue();
                double reward = close.subtract(target).doubleValue();
                double rrRatio = risk > 0 ? reward / risk : 0;
                if (rrRatio >= 1.0) {
                    // ─── CONFIDENCE SCORE FILTER ─────────────────────────────
                    // Score 0–100. Only emit signal if score >= 55.
                    // Filters weak-setup SL_HITs while preserving TARGET_HITs.
                    int score = 0;

                    // 1. ORB range quality (0–30 pts): larger range = stronger conviction
                    double orbRangePct = orbRange.doubleValue() / close.doubleValue() * 100.0;
                    if      (orbRangePct >= 1.0) score += 30;
                    else if (orbRangePct >= 0.5) score += 15;

                    // 2. Volume multiplier (0–25 pts): volume conviction
                    double volMult = (double) latest.volume() / Math.max(avgVol, 1);
                    if      (volMult >= 3.5) score += 25;
                    else if (volMult >= 2.5) score += 15;

                    // 3. Body ratio (0–20 pts): cleaner candle = higher quality signal
                    if      (bodyPct >= 0.85) score += 20;
                    else if (bodyPct >= 0.70) score += 10;

                    // 4. Signal type (0–15 pts): directBreakdown is stronger confirmation
                    score += directBreakdown ? 15 : 5;

                    // 5. Entry window (0–10 pts): early morning window is cleaner
                    if (istHour != null && istMinute != null) {
                        int min = istHour * 60 + istMinute;
                        if (min >= 9 * 60 + 30 && min < 9 * 60 + 45)  score += 10;
                        else if (min >= 10 * 60 + 0 && min <= 10 * 60 + 30) score += 5;
                    }

                    if (score < 75) {
                        log.debug("MSR filtered score={}/100 for {}", score, context.symbol());
                        return null;
                    }

                    String label = failedBreakout ? "FAILED_BREAKOUT" : "BREAKDOWN";
                    // Trail: activate at 1.2% profit, trail 0.5% behind best price
                    // Locks in gains when momentum continues past target
                    double trailTrigger  = 1.2;
                    double trailDistance = 0.5;
                    return new Signal(
                        context.symbol(), Signal.Side.SELL, close, sl, target, score / 100.0,
                        "MSR_SHORT " + label + " score=" + score + "/100"
                            + " @" + close.setScale(2, RoundingMode.HALF_UP)
                            + " orb=[" + orbLow.setScale(2, RoundingMode.HALF_UP) + "-" + orbHigh.setScale(2, RoundingMode.HALF_UP) + "]"
                            + " tgt=" + target + " rr=" + String.format("%.1f", rrRatio)
                            + " vol=" + String.format("%.1fx", volMult),
                        trailTrigger, trailDistance);
                }
            }
        }

        return null;
    }
}
