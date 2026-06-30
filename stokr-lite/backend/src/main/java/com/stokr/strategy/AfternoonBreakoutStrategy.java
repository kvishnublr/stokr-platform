package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Afternoon Consolidation Breakout — uncorrelated to the morning ORB session.
 *
 * Many stocks consolidate in a tight range from 10:30–12:00. When institutional
 * flow resumes after lunch and breaks that range with volume, the move extends
 * quickly because stops from the consolidation period all get hit in one direction.
 *
 * Window:  12:00–13:30 IST (post-lunch breakout window)
 * Range:   Defined by 10:30–12:00 candles (high/low of that consolidation)
 * Entry:   BUY above range high or SELL below range low with ≥3x volume
 * SL:      Back inside consolidation range (thesis invalidated)
 * Target:  1.2% from entry
 * Trail:   activates at 0.6%, distance 0.35%
 */
@Slf4j
@Component
public class AfternoonBreakoutStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "AFTERNOON_BREAKOUT"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 30) return null;

        // Time window: 12:00–13:30 IST
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour == null || istMinute == null) return null;
        int totalMin = istHour * 60 + istMinute;
        if (totalMin < 12 * 60 || totalMin > 13 * 60 + 30) return null;

        Candle latest = context.getLatestCandle();
        if (latest == null || latest.timestamp() == null) return null;

        BigDecimal close = latest.close();
        BigDecimal open  = latest.open();
        double px = close.doubleValue();
        if (px < 50 || px > 5000) return null;

        // Compute consolidation range from today's 10:30–12:00 candles
        LocalDate today = latest.timestamp().toLocalDate();
        BigDecimal consHigh = null, consLow = null;
        int consCandles = 0;
        for (Candle c : candles) {
            if (c.timestamp() == null) continue;
            if (!c.timestamp().toLocalDate().equals(today)) continue;
            int t = c.timestamp().getHour() * 60 + c.timestamp().getMinute();
            if (t < 10 * 60 + 30 || t >= 12 * 60) continue;
            consHigh = consHigh == null ? c.high() : consHigh.max(c.high());
            consLow  = consLow  == null ? c.low()  : consLow.min(c.low());
            consCandles++;
        }
        if (consHigh == null || consLow == null || consCandles < 10) return null;

        // Range must be meaningful: 0.4%–2.5% of price
        double rangeWidth = consHigh.subtract(consLow).doubleValue();
        double rangePct   = rangeWidth / px * 100.0;
        if (rangePct < 0.40 || rangePct > 2.5) return null;

        // Volume >= 3x 10-period avg
        long volSum = 0;
        int volLen = Math.min(10, n - 1);
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        if (avgVol == 0) return null;
        double volMult = (double) latest.volume() / avgVol;
        if (volMult < 3.0) return null;

        // Body >= 60%
        BigDecimal candleRange = latest.high().subtract(latest.low());
        if (candleRange.compareTo(BigDecimal.ZERO) == 0) return null;
        BigDecimal body = close.subtract(open).abs();
        double bodyPct = body.divide(candleRange, 4, RoundingMode.HALF_UP).doubleValue();
        if (bodyPct < 0.60) return null;

        boolean bullishCandle = close.compareTo(open) > 0;
        boolean bearishCandle = close.compareTo(open) < 0;

        // BUY breakout: close > consHigh (by tiny buffer) + bullish candle
        if (bullishCandle && close.compareTo(consHigh.multiply(BigDecimal.valueOf(1.0005))) > 0) {
            // SL: back inside range = consLow, capped at 0.7% max risk
            BigDecimal slRaw = consLow.subtract(consLow.multiply(BigDecimal.valueOf(0.001)));
            BigDecimal slCap = close.multiply(BigDecimal.valueOf(0.993)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal sl = slRaw.compareTo(slCap) > 0 ? slRaw.setScale(2, RoundingMode.HALF_UP) : slCap;
            BigDecimal target = close.multiply(BigDecimal.valueOf(1.012)).setScale(2, RoundingMode.HALF_UP);

            double risk   = close.subtract(sl).doubleValue();
            double reward = target.subtract(close).doubleValue();
            if (risk <= 0) return null;
            double rr = reward / risk;
            if (rr < 1.5) return null;

            int score = scoreSignal(rangePct, volMult, bodyPct, rr);
            if (score < 60) return null;

            return new Signal(
                context.symbol(), Signal.Side.BUY, close, sl, target, score / 100.0,
                "AFTERNOON_BREAKOUT BUY range=" + String.format("%.2f%%", rangePct)
                    + " vol=" + String.format("%.1fx", volMult)
                    + " body=" + String.format("%.0f%%", bodyPct * 100)
                    + " score=" + score + "/100"
                    + " @" + close.setScale(2, RoundingMode.HALF_UP)
                    + " range=[" + consLow.setScale(2, RoundingMode.HALF_UP)
                    + "-" + consHigh.setScale(2, RoundingMode.HALF_UP) + "]"
                    + " sl=" + sl + " tgt=" + target + " rr=" + String.format("%.1f", rr),
                0.6, 0.35);
        }

        // SELL breakout: close < consLow (by tiny buffer) + bearish candle
        if (bearishCandle && close.compareTo(consLow.multiply(BigDecimal.valueOf(0.9995))) < 0) {
            BigDecimal slRaw = consHigh.add(consHigh.multiply(BigDecimal.valueOf(0.001)));
            BigDecimal slCap = close.multiply(BigDecimal.valueOf(1.007)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal sl = slRaw.compareTo(slCap) < 0 ? slRaw.setScale(2, RoundingMode.HALF_UP) : slCap;
            BigDecimal target = close.multiply(BigDecimal.valueOf(0.988)).setScale(2, RoundingMode.HALF_UP);

            double risk   = sl.subtract(close).doubleValue();
            double reward = close.subtract(target).doubleValue();
            if (risk <= 0) return null;
            double rr = reward / risk;
            if (rr < 1.5) return null;

            int score = scoreSignal(rangePct, volMult, bodyPct, rr);
            if (score < 60) return null;

            return new Signal(
                context.symbol(), Signal.Side.SELL, close, sl, target, score / 100.0,
                "AFTERNOON_BREAKOUT SELL range=" + String.format("%.2f%%", rangePct)
                    + " vol=" + String.format("%.1fx", volMult)
                    + " body=" + String.format("%.0f%%", bodyPct * 100)
                    + " score=" + score + "/100"
                    + " @" + close.setScale(2, RoundingMode.HALF_UP)
                    + " range=[" + consLow.setScale(2, RoundingMode.HALF_UP)
                    + "-" + consHigh.setScale(2, RoundingMode.HALF_UP) + "]"
                    + " sl=" + sl + " tgt=" + target + " rr=" + String.format("%.1f", rr),
                0.6, 0.35);
        }

        return null;
    }

    private int scoreSignal(double rangePct, double volMult, double bodyPct, double rr) {
        int score = 0;
        // Tight consolidation (0-25): tight ranges break more cleanly
        if      (rangePct >= 0.4 && rangePct <= 0.8) score += 25;
        else if (rangePct <= 1.2) score += 18;
        else if (rangePct <= 2.0) score += 10;
        // Volume (0-30)
        if      (volMult >= 5.0) score += 30;
        else if (volMult >= 4.0) score += 22;
        else if (volMult >= 3.0) score += 15;
        // Body % (0-25)
        if      (bodyPct >= 0.80) score += 25;
        else if (bodyPct >= 0.70) score += 18;
        else if (bodyPct >= 0.60) score += 10;
        // RR (0-20)
        if      (rr >= 2.5) score += 20;
        else if (rr >= 2.0) score += 14;
        else if (rr >= 1.5) score += 8;
        return score;
    }
}
