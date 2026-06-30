package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * VWAP Dip Buy — buys institutional VWAP support on bullish NIFTY days.
 *
 * When NIFTY is up >0.2% (bullish day), strong stocks pull back to VWAP and
 * bounce — this is institutions buying the dip. Entry at the VWAP bounce
 * with SL just below VWAP (thesis invalidated if support breaks).
 *
 * Window:  10:30–12:00 IST (after ORB resolved, before lunch drift)
 * Entry:   BUY only when close is 0–0.35% above VWAP with a bullish candle
 * SL:      0.25% below VWAP (support broken = thesis wrong)
 * Target:  1% above entry
 * Trail:   activates at 0.5%, distance 0.3%
 */
@Slf4j
@Component
public class VwapDipBuyStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "VWAP_DIP_BUY"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 20) return null;

        // Gate: NIFTY must be up >0.2% — bullish day only
        Double niftyPct = context.extra("niftyPctChange", Double.class);
        if (niftyPct == null || niftyPct < 0.20) return null;

        // Time window: 10:30–12:00 IST
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour == null || istMinute == null) return null;
        int totalMin = istHour * 60 + istMinute;
        if (totalMin < 10 * 60 + 30 || totalMin > 12 * 60) return null;

        Candle latest = context.getLatestCandle();
        Candle prev   = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        BigDecimal close = latest.close();
        BigDecimal open  = latest.open();
        double px = close.doubleValue();
        if (px < 50 || px > 5000) return null;

        // Use running VWAP from context (computed by BacktestController per-candle)
        BigDecimal vwap = context.vwap();
        if (vwap == null || vwap.compareTo(BigDecimal.ZERO) == 0) return null;

        double vwapD = vwap.doubleValue();

        // Close must be 0–0.35% above VWAP (touching VWAP support zone from above)
        double distFromVwap = (px - vwapD) / vwapD * 100.0;
        if (distFromVwap < 0.0 || distFromVwap > 0.35) return null;

        // Must be a bullish candle (close > open)
        if (close.compareTo(open) <= 0) return null;

        // Body >= 70% of candle range
        BigDecimal range = latest.high().subtract(latest.low());
        if (range.compareTo(BigDecimal.ZERO) == 0) return null;
        BigDecimal body = close.subtract(open).abs();
        double bodyPct = body.divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        if (bodyPct < 0.70) return null;

        // Volume >= 2.5x 10-period average
        long volSum = 0;
        int volLen = Math.min(10, n - 1);
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        if (avgVol == 0) return null;
        double volMult = (double) latest.volume() / avgVol;
        if (volMult < 2.5) return null;

        // SL: 0.25% below VWAP (support level)
        BigDecimal sl = vwap.multiply(BigDecimal.valueOf(0.9975)).setScale(2, RoundingMode.HALF_UP);
        // Target: 1% above entry
        BigDecimal target = close.multiply(BigDecimal.valueOf(1.01)).setScale(2, RoundingMode.HALF_UP);

        double risk   = close.subtract(sl).doubleValue();
        double reward = target.subtract(close).doubleValue();
        if (risk <= 0) return null;
        double rr = reward / risk;
        if (rr < 1.5) return null;

        int score = scoreSignal(niftyPct, distFromVwap, volMult, bodyPct, rr);
        if (score < 65) return null;

        return new Signal(
            context.symbol(), Signal.Side.BUY, close, sl, target, score / 100.0,
            "VWAP_DIP_BUY NIFTY=" + String.format("+%.2f%%", niftyPct)
                + " distVwap=" + String.format("%.2f%%", distFromVwap)
                + " vol=" + String.format("%.1fx", volMult)
                + " body=" + String.format("%.0f%%", bodyPct * 100)
                + " score=" + score + "/100"
                + " @" + close.setScale(2, RoundingMode.HALF_UP)
                + " vwap=" + vwap.setScale(2, RoundingMode.HALF_UP)
                + " sl=" + sl + " tgt=" + target + " rr=" + String.format("%.1f", rr),
            0.5, 0.3);
    }

    private int scoreSignal(double niftyPct, double distFromVwap, double volMult, double bodyPct, double rr) {
        int score = 0;
        // NIFTY strength (0-25)
        if      (niftyPct >= 0.60) score += 25;
        else if (niftyPct >= 0.40) score += 18;
        else if (niftyPct >= 0.20) score += 10;
        // VWAP proximity (0-25) — tighter is better RR
        if      (distFromVwap <= 0.10) score += 25;
        else if (distFromVwap <= 0.20) score += 18;
        else if (distFromVwap <= 0.35) score += 10;
        // Volume (0-25)
        if      (volMult >= 4.0) score += 25;
        else if (volMult >= 3.0) score += 18;
        else if (volMult >= 2.5) score += 12;
        // Body % (0-15)
        if      (bodyPct >= 0.85) score += 15;
        else if (bodyPct >= 0.70) score += 8;
        // RR (0-10)
        if      (rr >= 2.5) score += 10;
        else if (rr >= 1.8) score += 6;
        else if (rr >= 1.5) score += 3;
        return score;
    }
}
