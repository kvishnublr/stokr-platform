package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Momentum Trail Strategy — ORB real breakout with trailing stop.
 * Opposite of SMF: instead of fading failed breakouts, RIDE confirmed ones.
 *
 * Setup (LONG):
 *   prev: high > orbHigh + 0.15%, volume >= 3x 20-bar avg  <- breakout candle
 *   curr: bullish (close > open), close > orbHigh           <- confirms hold
 *   SL  : just below orbHigh (if back below ORB, breakout failed)
 *   Trail: activates at 1% profit, trails 0.5% from peak
 *   Target: 6x risk ceiling (trail exits well before this)
 *
 * Setup (SHORT): mirror at orbLow.
 */
@Slf4j
@Component
public class MomentumTrailStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "MOMENTUM_TRAIL"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 20) return null;

        BigDecimal orbHigh  = context.extra("orbHigh",  BigDecimal.class);
        BigDecimal orbLow   = context.extra("orbLow",   BigDecimal.class);
        BigDecimal orbRange = context.extra("orbRange",  BigDecimal.class);
        if (orbHigh == null || orbLow == null || orbRange == null) return null;

        Candle curr  = candles.get(n - 1);
        BigDecimal close = curr.close();

        // ORB range >= 0.3% — need some intraday range to trade
        if (orbRange.doubleValue() / close.doubleValue() < 0.003) return null;

        // Window: 9:30–10:15 IST — first hour after ORB, momentum fades quickly
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 9 * 60 + 30 || min > 10 * 60 + 15) return null;
        }

        Candle prev = candles.get(n - 2);

        // 20-bar volume avg (excluding prev candle to avoid self-reference)
        int volLen = Math.min(20, n - 2);
        long volSum = 0;
        for (int k = n - 2 - volLen; k < n - 2; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;

        // ─── LONG: FRESH ORB breakout — prev is the FIRST candle to close above orbHigh
        // The candle before prev must have been at or below orbHigh (fresh, not already running)
        Candle prevPrev = n >= 3 ? candles.get(n - 3) : null;
        boolean freshLongBreakout = prevPrev != null && prevPrev.close().compareTo(orbHigh) <= 0;

        double longSpikeH = prev.close().subtract(orbHigh).doubleValue() / close.doubleValue();
        if (freshLongBreakout
                && prev.close().compareTo(orbHigh) > 0
                && longSpikeH >= 0.001
                && prev.volume() >= avgVol * 4
                && prev.close().compareTo(prev.open()) > 0) {  // prev was bullish (momentum candle)

            // Curr candle: also bullish AND continues above orbHigh
            if (close.compareTo(curr.open()) > 0 && close.compareTo(orbHigh) > 0) {

                // SL just below orbHigh — if back below ORB, breakout failed
                BigDecimal sl = orbHigh.multiply(BigDecimal.valueOf(0.998))
                    .setScale(2, RoundingMode.HALF_UP);
                double risk = close.subtract(sl).doubleValue();
                if (risk <= 0) return null;
                // Skip if entry is too far from ORB level (chasing late)
                if (risk / close.doubleValue() > 0.008) return null;

                // Target ceiling = 6x risk (trailing stop exits before this in big moves)
                BigDecimal target = close.add(BigDecimal.valueOf(6.0 * risk))
                    .setScale(2, RoundingMode.HALF_UP);

                return new Signal(context.symbol(), Signal.Side.BUY, close, sl, target,
                    0.72,
                    "MT_LONG @" + close.setScale(2, RoundingMode.HALF_UP)
                        + " orb=" + orbHigh.setScale(2, RoundingMode.HALF_UP)
                        + " sl=" + sl
                        + " risk=" + String.format("%.2f%%", risk / close.doubleValue() * 100)
                        + " vol=" + String.format("%.1fx", (double) prev.volume() / avgVol),
                    1.0, 0.5);   // trail: trigger at 1% profit, trail 0.5% from peak
            }
        }

        // ─── SHORT: FRESH ORB breakdown — prev is the first candle to close below orbLow
        boolean freshShortBreakdown = prevPrev != null && prevPrev.close().compareTo(orbLow) >= 0;

        double shortSpikeH = orbLow.subtract(prev.close()).doubleValue() / close.doubleValue();
        if (freshShortBreakdown
                && prev.close().compareTo(orbLow) < 0
                && shortSpikeH >= 0.001
                && prev.volume() >= avgVol * 4
                && prev.close().compareTo(prev.open()) < 0) {  // prev was bearish

            // Curr candle: bearish AND continues below orbLow
            if (close.compareTo(curr.open()) < 0 && close.compareTo(orbLow) < 0) {

                BigDecimal sl = orbLow.multiply(BigDecimal.valueOf(1.002))
                    .setScale(2, RoundingMode.HALF_UP);
                double risk = sl.subtract(close).doubleValue();
                if (risk <= 0) return null;
                if (risk / close.doubleValue() > 0.008) return null;

                BigDecimal target = close.subtract(BigDecimal.valueOf(6.0 * risk))
                    .setScale(2, RoundingMode.HALF_UP);
                if (target.compareTo(close) >= 0) return null;

                return new Signal(context.symbol(), Signal.Side.SELL, close, sl, target,
                    0.72,
                    "MT_SHORT @" + close.setScale(2, RoundingMode.HALF_UP)
                        + " orb=" + orbLow.setScale(2, RoundingMode.HALF_UP)
                        + " sl=" + sl
                        + " risk=" + String.format("%.2f%%", risk / close.doubleValue() * 100)
                        + " vol=" + String.format("%.1fx", (double) prev.volume() / avgVol),
                    1.0, 0.5);
            }
        }

        return null;
    }
}
