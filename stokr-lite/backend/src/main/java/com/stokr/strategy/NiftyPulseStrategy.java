package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * NIFTY Pulse Alignment — ORB strategy gated by NIFTY 50 intraday direction.
 *
 * At 10:00–10:30 IST, check NIFTY % change from previous day's close:
 *   NIFTY down >0.3% → SHORT stocks breaking ORB low (same as MSR logic)
 *   NIFTY up   >0.3% → LONG  stocks breaking ORB high (bullish breakout)
 *   NIFTY flat ±0.3% → sit out entirely
 *
 * Hypothesis: ORB signals taken WITH the NIFTY trend have higher follow-through
 * than signals taken without a market conviction filter.
 *
 * Trail: activates after target hit, 0.5% behind best price (same as MSR).
 */
@Slf4j
@Component
public class NiftyPulseStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "NIFTY_PULSE";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 20) return null;

        // Gate by NIFTY direction — only SHORT when market is clearly down; LONG removed (tested, 32% WR)
        Double niftyPct = context.extra("niftyPctChange", Double.class);
        if (niftyPct == null) return null;

        // 0.2% threshold: captures more bearish days than 0.3% while still filtering flat/bullish days
        if (niftyPct > -0.2) return null;

        BigDecimal orbHigh  = context.extra("orbHigh",  BigDecimal.class);
        BigDecimal orbLow   = context.extra("orbLow",   BigDecimal.class);
        BigDecimal orbRange = context.extra("orbRange",  BigDecimal.class);
        if (orbHigh == null || orbLow == null || orbRange == null) return null;

        // Minimum 0.5% ORB range
        Candle tmpClose = candles.get(n - 1);
        if (orbRange.doubleValue() / tmpClose.close().doubleValue() < 0.005) return null;

        // Only 10:00–10:30 IST
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 10 * 60 || min > 10 * 60 + 30) return null;
        }

        Candle latest = context.getLatestCandle();
        Candle prev   = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        BigDecimal close = latest.close();
        BigDecimal prevDayClose = context.extra("prevDayClose", BigDecimal.class);
        BigDecimal dayOpen      = context.extra("dayOpen", BigDecimal.class);

        // Volume >= 2.5x 10-period average
        long volSum = 0;
        int volLen = Math.min(10, n - 1);
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        if (avgVol == 0 || latest.volume() < avgVol * 2.5) return null;

        // Strong body >= 70%
        BigDecimal range = latest.high().subtract(latest.low());
        double bodyPct = 0;
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal body = latest.open().subtract(close).abs();
            bodyPct = body.divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        }
        if (bodyPct < 0.70) return null;

        double orbRangePct = orbRange.doubleValue() / close.doubleValue() * 100.0;
        double volMult     = (double) latest.volume() / Math.max(avgVol, 1);

        // ─── SHORT: market down >0.2% — stock breaks ORB low ────────────────────
        // Skip gap-up days >1.5% (bullish gap fights shorts even on bearish NIFTY days)
        if (prevDayClose != null && dayOpen != null && prevDayClose.compareTo(BigDecimal.ZERO) > 0) {
            double gapUp = dayOpen.subtract(prevDayClose).doubleValue() / prevDayClose.doubleValue();
            if (gapUp > 0.015) return null;
        }

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
                    int score = scoreSignal(orbRangePct, volMult, bodyPct, directBreakdown, rrRatio);
                    if (score < 75) return null;

                    String label = failedBreakout ? "FAILED_BREAKOUT" : "BREAKDOWN";
                    return new Signal(
                        context.symbol(), Signal.Side.SELL, close, sl, target, score / 100.0,
                        "NPA_SHORT " + label + " NIFTY=" + String.format("%.2f%%", niftyPct)
                            + " score=" + score + "/100"
                            + " @" + close.setScale(2, RoundingMode.HALF_UP)
                            + " orb=[" + orbLow.setScale(2, RoundingMode.HALF_UP) + "-" + orbHigh.setScale(2, RoundingMode.HALF_UP) + "]"
                            + " tgt=" + target + " rr=" + String.format("%.1f", rrRatio),
                        999.0, 0.5);
                }
            }
        }

        return null;
    }

    private int scoreSignal(double orbRangePct, double volMult, double bodyPct, boolean directSignal, double rrRatio) {
        int score = 0;
        if      (orbRangePct >= 1.0) score += 30;
        else if (orbRangePct >= 0.6) score += 15;
        if      (volMult >= 3.5) score += 25;
        else if (volMult >= 2.5) score += 15;
        if      (bodyPct >= 0.85) score += 20;
        else if (bodyPct >= 0.70) score += 10;
        score += directSignal ? 15 : 5;
        if      (rrRatio >= 2.0) score += 10;
        else if (rrRatio >= 1.5) score += 5;
        return score;
    }
}
