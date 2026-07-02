package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Morning Surge Reversal — SHORT-only ORB breakdown strategy.
 *
 * Setup: price breaks below orbLow (direct momentum) OR briefly went above
 * orbHigh then failed (bull trap). Window: 10:00–10:30 IST only.
 * LONG breakout tested and rejected — buying orbHigh at 10:00 is too late
 * (breakout already ran), 38% WR vs 61% for SHORT.
 *
 * SL: orbHigh + 0.1%, Target: orbLow - 1.5 × orbRange
 * Trail: activates after target hit, 0.5% behind best price.
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

        // Minimum 0.5% ORB range — small ORBs give tiny targets
        Candle tmpClose = candles.get(n - 1);
        if (orbRange.doubleValue() / tmpClose.close().doubleValue() < 0.005) return null;

        // Only 10:00–10:30 IST — 9:30-9:44 removed (backtest: -₹1,259 net, 48.9% WR)
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour == null || istMinute == null) return null;
        int min = istHour * 60 + istMinute;
        if (min < 10 * 60 + 0 || min > 10 * 60 + 30) return null;

        Candle latest = context.getLatestCandle();
        Candle prev   = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        BigDecimal close = latest.close();

        // Skip gap-up days >1.5% — bullish momentum fights shorts
        BigDecimal prevDayClose = context.extra("prevDayClose", BigDecimal.class);
        BigDecimal dayOpen      = context.extra("dayOpen", BigDecimal.class);
        if (prevDayClose != null && dayOpen != null && prevDayClose.compareTo(BigDecimal.ZERO) > 0) {
            double gapUp = dayOpen.subtract(prevDayClose).doubleValue() / prevDayClose.doubleValue();
            if (gapUp > 0.015) return null;
        }

        // Volume >= 2.5x 10-period average
        long volSum = 0;
        int volLen = Math.min(10, n - 1);
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        if (avgVol == 0 || latest.volume() < avgVol * 2.5) return null;

        // Strong bearish body (>= 70%)
        BigDecimal range = latest.high().subtract(latest.low());
        double bodyPct = 0;
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal body = latest.open().subtract(close).abs();
            bodyPct = body.divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        }
        if (bodyPct < 0.70) return null;

        // ─── SHORT: failed upside breakout OR direct breakdown ────────────────
        boolean failedBreakout  = close.compareTo(orbHigh) <= 0 && prev.close().compareTo(orbHigh) > 0;
        boolean directBreakdown = close.compareTo(orbLow) <= 0;

        if ((failedBreakout || directBreakdown) && close.compareTo(latest.open()) < 0) {
            BigDecimal sl     = orbHigh.multiply(BigDecimal.valueOf(1.001)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal target = orbLow.subtract(orbRange.multiply(BigDecimal.valueOf(1.5))).setScale(2, RoundingMode.HALF_UP);

            if (target.compareTo(close) < 0 && sl.compareTo(close) > 0) {
                double risk    = sl.subtract(close).doubleValue();
                double reward  = close.subtract(target).doubleValue();
                double rrRatio = risk > 0 ? reward / risk : 0;
                if (rrRatio >= 1.0) {
                    int score = 0;
                    double orbRangePct = orbRange.doubleValue() / close.doubleValue() * 100.0;
                    double volMult     = (double) latest.volume() / Math.max(avgVol, 1);

                    if      (orbRangePct >= 1.0) score += 30;
                    else if (orbRangePct >= 0.6) score += 15;
                    if      (volMult >= 3.5) score += 25;
                    else if (volMult >= 2.5) score += 15;
                    if      (bodyPct >= 0.85) score += 20;
                    else if (bodyPct >= 0.70) score += 10;
                    score += directBreakdown ? 15 : 5;
                    if      (rrRatio >= 2.0) score += 10;
                    else if (rrRatio >= 1.5) score += 5;

                    if (score < 75) {
                        log.debug("MSR filtered score={}/100 for {}", score, context.symbol());
                        return null;
                    }

                    String label = failedBreakout ? "FAILED_BREAKOUT" : "BREAKDOWN";
                    return new Signal(
                        context.symbol(), Signal.Side.SELL, close, sl, target, score / 100.0,
                        "MSR_SHORT " + label + " score=" + score + "/100"
                            + " @" + close.setScale(2, RoundingMode.HALF_UP)
                            + " orb=[" + orbLow.setScale(2, RoundingMode.HALF_UP) + "-" + orbHigh.setScale(2, RoundingMode.HALF_UP) + "]"
                            + " tgt=" + target + " rr=" + String.format("%.1f", rrRatio)
                            + " vol=" + String.format("%.1fx", volMult),
                        999.0, 0.5);
                }
            }
        }

        return null;
    }
}
