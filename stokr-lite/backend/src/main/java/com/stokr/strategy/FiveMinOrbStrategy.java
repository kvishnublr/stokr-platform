package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 5-Minute ORB Scalp — catches fresh breakouts right after the open.
 *
 * Opening Range = high/low of the first 5 candles (9:15–9:19 IST).
 * Signal window: 9:20–9:45 IST — the breakout is fresh, momentum still building.
 * This is fundamentally different from 1-hour ORB: entry at 9:22 vs 10:05.
 *
 * BUY:  close breaks above 5-min high with 2x volume + bullish candle
 * SELL: close breaks below 5-min low  with 2x volume + bearish candle
 * SL: opposite end of 5-min range, Target: entry ± 2× range
 * Trail: activates after 0.5% gain, 0.3% distance
 */
@Slf4j
@Component
public class FiveMinOrbStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "FIVE_MIN_ORB"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 6) return null;

        Candle latest = context.getLatestCandle();
        if (latest == null || latest.timestamp() == null) return null;

        // Window: 9:20–9:45 IST only (fresh breakout after 5-min ORB is set)
        int latestH = latest.timestamp().getHour();
        int latestM = latest.timestamp().getMinute();
        int latestMin = latestH * 60 + latestM;
        if (latestMin < 9 * 60 + 20 || latestMin > 9 * 60 + 45) return null;

        // Build 5-min ORB from today's 9:15–9:19 candles
        LocalDate today = latest.timestamp().toLocalDate();
        BigDecimal orb5High = null, orb5Low = null;
        int orbCount = 0;

        for (Candle c : candles) {
            if (c.timestamp() == null) continue;
            if (!c.timestamp().toLocalDate().equals(today)) continue;
            int t = c.timestamp().getHour() * 60 + c.timestamp().getMinute();
            if (t < 9 * 60 + 15 || t >= 9 * 60 + 20) continue;
            orb5High = orb5High == null ? c.high() : orb5High.max(c.high());
            orb5Low  = orb5Low  == null ? c.low()  : orb5Low.min(c.low());
            orbCount++;
        }

        if (orb5High == null || orb5Low == null || orbCount < 3) return null;

        BigDecimal orb5Range = orb5High.subtract(orb5Low);
        double rangePct = orb5Range.doubleValue() / orb5High.doubleValue() * 100.0;
        if (rangePct < 0.3) return null; // too tight a range = no meaningful move

        // Volume: 2x the recent avg (breakout must have conviction)
        long volSum = 0;
        int volLen = Math.min(5, n - 1);
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        if (avgVol == 0 || latest.volume() < avgVol * 2.0) return null;

        BigDecimal close = latest.close();
        double px = close.doubleValue();
        if (px < 100 || px > 3000) return null;

        double volMult = (double) latest.volume() / Math.max(avgVol, 1);

        // BUY: price breaks above 5-min high with bullish candle
        if (close.compareTo(orb5High) > 0 && close.compareTo(latest.open()) > 0) {
            BigDecimal sl     = orb5Low.multiply(BigDecimal.valueOf(0.999)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal target = orb5High.add(orb5Range.multiply(BigDecimal.valueOf(2.0))).setScale(2, RoundingMode.HALF_UP);
            if (target.compareTo(close) <= 0 || sl.compareTo(close) >= 0) return null;
            double risk   = close.subtract(sl).doubleValue();
            double reward = target.subtract(close).doubleValue();
            double rr     = risk > 0 ? reward / risk : 0;
            if (rr < 1.0) return null;
            return new Signal(context.symbol(), Signal.Side.BUY, close, sl, target, 0.75,
                "5ORB BUY @" + close.setScale(2, RoundingMode.HALF_UP)
                    + " orb=[" + orb5Low.setScale(2, RoundingMode.HALF_UP) + "-" + orb5High.setScale(2, RoundingMode.HALF_UP) + "]"
                    + " vol=" + String.format("%.0fx", volMult) + " rr=" + String.format("%.1f", rr),
                0.5, 0.3);
        }

        // SELL: price breaks below 5-min low with bearish candle
        if (close.compareTo(orb5Low) < 0 && close.compareTo(latest.open()) < 0) {
            BigDecimal sl     = orb5High.multiply(BigDecimal.valueOf(1.001)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal target = orb5Low.subtract(orb5Range.multiply(BigDecimal.valueOf(2.0))).setScale(2, RoundingMode.HALF_UP);
            if (target.compareTo(close) >= 0 || sl.compareTo(close) <= 0) return null;
            double risk   = sl.subtract(close).doubleValue();
            double reward = close.subtract(target).doubleValue();
            double rr     = risk > 0 ? reward / risk : 0;
            if (rr < 1.0) return null;
            return new Signal(context.symbol(), Signal.Side.SELL, close, sl, target, 0.75,
                "5ORB SELL @" + close.setScale(2, RoundingMode.HALF_UP)
                    + " orb=[" + orb5Low.setScale(2, RoundingMode.HALF_UP) + "-" + orb5High.setScale(2, RoundingMode.HALF_UP) + "]"
                    + " vol=" + String.format("%.0fx", volMult) + " rr=" + String.format("%.1f", rr),
                0.5, 0.3);
        }

        return null;
    }
}
