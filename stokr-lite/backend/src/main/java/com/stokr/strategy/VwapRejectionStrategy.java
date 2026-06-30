package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * VWAP Rejection SHORT — fades failed VWAP reclaim attempts on bearish days.
 *
 * VWAP is the institutional "fair value" for the day. When a stock is already
 * trading BELOW VWAP (sellers in control), bounces up to test it, but is
 * REJECTED there (closes back below), institutions are actively defending
 * VWAP as resistance. High volume on the rejection candle confirms selling.
 *
 * Conceptually identical edge to MSR/NPA but applied mid-day:
 *   MSR: price breaks above ORB high → fails → SHORT (fade false ORB breakout)
 *   VRS: price bounces up to VWAP → fails → SHORT (fade failed VWAP reclaim)
 *
 * Window:  11:00–14:00 IST (VWAP needs 90+ min of data to be meaningful)
 * Gate:    NIFTY down >0.1% (bearish day — sellers already winning at index level)
 * Entry:   SHORT when high touches VWAP but close is rejected below it
 * SL:      VWAP + 0.2% (VWAP break = thesis wrong)
 * Target:  1% below entry
 * Trail:   activates at 0.5%, distance 0.3%
 */
@Slf4j
@Component
public class VwapRejectionStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "VWAP_REJECTION"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 20) return null;

        // Gate: NIFTY must be down >0.1% (bearish day — sellers in control at index)
        Double niftyPct = context.extra("niftyPctChange", Double.class);
        if (niftyPct == null || niftyPct > -0.10) return null;

        // Time window: 11:00–14:00 IST (VWAP needs enough data to be meaningful)
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour == null || istMinute == null) return null;
        int totalMin = istHour * 60 + istMinute;
        if (totalMin < 11 * 60 || totalMin > 14 * 60) return null;

        // Running VWAP from BacktestController (typicalPrice × volume cumulative)
        BigDecimal vwap = context.vwap();
        if (vwap == null || vwap.compareTo(BigDecimal.ZERO) == 0) return null;

        Candle latest = context.getLatestCandle();
        Candle prev   = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        BigDecimal close = latest.close();
        BigDecimal open  = latest.open();
        BigDecimal high  = latest.high();
        BigDecimal low   = latest.low();
        double px = close.doubleValue();
        double vwapD = vwap.doubleValue();
        if (px < 50 || px > 5000) return null;

        // Stock must be BELOW VWAP (bearish structure: sellers in control for the day)
        if (close.compareTo(vwap) >= 0) return null;

        // Previous candle also below VWAP (not a fresh cross — established below-VWAP structure)
        BigDecimal prevVwap1 = context.extra("prevVwap1", BigDecimal.class);
        if (prevVwap1 != null && prev.close().compareTo(prevVwap1) >= 0) return null;

        // Rejection pattern: candle HIGH reached VWAP zone (bounce attempted)
        // but CLOSE stayed below VWAP (rejection confirmed)
        double highD = high.doubleValue();
        double highDistFromVwap = (highD - vwapD) / vwapD * 100.0; // positive = touched above
        if (highDistFromVwap < -0.10) return null; // high didn't get close enough to VWAP (no real bounce attempt)

        // Close must be below VWAP (rejection)
        double closeDistFromVwap = (px - vwapD) / vwapD * 100.0; // negative = below VWAP
        if (closeDistFromVwap >= -0.05) return null; // too close to VWAP — ambiguous

        // Close within 0.4% below VWAP for decent RR (too far below = risk is too wide)
        if (closeDistFromVwap < -0.40) return null;

        // Must be a bearish candle (close < open)
        if (close.compareTo(open) >= 0) return null;

        // Body >= 65% of candle range
        BigDecimal range = high.subtract(low);
        if (range.compareTo(BigDecimal.ZERO) == 0) return null;
        BigDecimal body = open.subtract(close).abs();
        double bodyPct = body.divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        if (bodyPct < 0.65) return null;

        // Volume >= 2.5x 10-period average (active selling into VWAP = institutional distribution)
        long volSum = 0;
        int volLen = Math.min(10, n - 1);
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        if (avgVol == 0) return null;
        double volMult = (double) latest.volume() / avgVol;
        if (volMult < 2.5) return null;

        // Skip gap-down stocks >1.5% (stocks already beaten up have weaker signals)
        BigDecimal prevDayClose = context.extra("prevDayClose", BigDecimal.class);
        BigDecimal dayOpen      = context.extra("dayOpen",      BigDecimal.class);
        if (prevDayClose != null && dayOpen != null && prevDayClose.compareTo(BigDecimal.ZERO) > 0) {
            double gapDown = dayOpen.subtract(prevDayClose).doubleValue() / prevDayClose.doubleValue();
            if (gapDown < -0.015) return null;
        }

        // SL: VWAP + 0.2% (above VWAP = thesis wrong, sellers not defending)
        BigDecimal sl = vwap.multiply(BigDecimal.valueOf(1.002)).setScale(2, RoundingMode.HALF_UP);
        // Target: 1% below entry
        BigDecimal target = close.multiply(BigDecimal.valueOf(0.99)).setScale(2, RoundingMode.HALF_UP);

        double risk   = sl.subtract(close).doubleValue();
        double reward = close.subtract(target).doubleValue();
        if (risk <= 0) return null;
        double rr = reward / risk;
        if (rr < 1.5) return null;

        int score = scoreSignal(niftyPct, closeDistFromVwap, volMult, bodyPct, rr);
        if (score < 65) return null;

        return new Signal(
            context.symbol(), Signal.Side.SELL, close, sl, target, score / 100.0,
            "VWAP_REJ SHORT NIFTY=" + String.format("%.2f%%", niftyPct)
                + " highVwapDist=" + String.format("%.2f%%", highDistFromVwap)
                + " closeVwapDist=" + String.format("%.2f%%", closeDistFromVwap)
                + " vol=" + String.format("%.1fx", volMult)
                + " body=" + String.format("%.0f%%", bodyPct * 100)
                + " score=" + score + "/100"
                + " @" + close.setScale(2, RoundingMode.HALF_UP)
                + " vwap=" + vwap.setScale(2, RoundingMode.HALF_UP)
                + " sl=" + sl + " tgt=" + target + " rr=" + String.format("%.1f", rr),
            0.5, 0.3);
    }

    private int scoreSignal(double niftyPct, double closeDistFromVwap, double volMult, double bodyPct, double rr) {
        int score = 0;
        // NIFTY bearishness (0-25): more negative = better context for short
        if      (niftyPct <= -0.60) score += 25;
        else if (niftyPct <= -0.30) score += 18;
        else if (niftyPct <= -0.10) score += 10;
        // VWAP proximity (0-25): closer to VWAP = tighter risk = better RR
        if      (closeDistFromVwap >= -0.10) score += 25;
        else if (closeDistFromVwap >= -0.20) score += 18;
        else if (closeDistFromVwap >= -0.40) score += 10;
        // Volume (0-25): higher = more institutional participation in rejection
        if      (volMult >= 4.0) score += 25;
        else if (volMult >= 3.0) score += 18;
        else if (volMult >= 2.5) score += 12;
        // Body % (0-15): stronger bearish body = conviction
        if      (bodyPct >= 0.85) score += 15;
        else if (bodyPct >= 0.65) score += 8;
        // RR (0-10)
        if      (rr >= 3.0) score += 10;
        else if (rr >= 2.0) score += 6;
        else if (rr >= 1.5) score += 3;
        return score;
    }
}
