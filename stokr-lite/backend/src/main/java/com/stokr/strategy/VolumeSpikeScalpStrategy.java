package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Volume Spike Scalp — catches stocks that are ABOUT TO JUMP right now.
 *
 * A sudden 8x+ volume spike on a single 1-min candle signals institutional
 * order flow hitting the market. Price almost always follows through for 0.8–1%.
 *
 * Conditions:
 *   - Volume >= 8x the 10-bar average on the signal candle
 *   - Strong directional body >= 85% of candle range
 *   - No recent gap of >1.5% already run (don't chase stale moves)
 *   - Window: 9:15–12:00 IST (morning momentum hours only)
 *
 * BUY on bullish spike, SELL on bearish spike.
 * Target: 1%, SL: spike candle's opposite wick + buffer.
 * Trail: activates at 0.5% gain, 0.3% distance — locks in fast.
 */
@Slf4j
@Component
public class VolumeSpikeScalpStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "VOLUME_SPIKE_SCALP"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 12) return null;

        // Window: 9:15–12:00 IST
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 9 * 60 + 15 || min > 12 * 60) return null;
        }

        Candle latest = context.getLatestCandle();
        if (latest == null) return null;

        BigDecimal close = latest.close();
        double px = close.doubleValue();
        if (px < 100 || px > 3000) return null;

        // Volume spike: >= 8x the 10-period average
        long volSum = 0;
        int volLen = Math.min(10, n - 1);
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        if (avgVol == 0) return null;
        double volMult = (double) latest.volume() / avgVol;
        if (volMult < 8.0) return null;

        // Very strong directional body: >= 85%
        BigDecimal candleRange = latest.high().subtract(latest.low());
        if (candleRange.compareTo(BigDecimal.ZERO) == 0) return null;
        double bodyPct = latest.open().subtract(close).abs()
            .divide(candleRange, 4, RoundingMode.HALF_UP).doubleValue();
        if (bodyPct < 0.85) return null;

        // Don't chase if price already moved >1.5% in last 5 candles (stale spike)
        if (n >= 6) {
            double priceBefore = candles.get(n - 6).close().doubleValue();
            if (Math.abs(px - priceBefore) / priceBefore * 100 > 1.5) return null;
        }

        boolean bullish = close.compareTo(latest.open()) > 0;

        if (bullish) {
            BigDecimal sl     = latest.low().multiply(BigDecimal.valueOf(0.998)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal target = close.multiply(BigDecimal.valueOf(1.01)).setScale(2, RoundingMode.HALF_UP);
            if (sl.compareTo(close) >= 0 || target.compareTo(close) <= 0) return null;
            double risk   = close.subtract(sl).doubleValue();
            double reward = target.subtract(close).doubleValue();
            double rr     = risk > 0 ? reward / risk : 0;
            if (rr < 1.5) return null;
            return new Signal(context.symbol(), Signal.Side.BUY, close, sl, target, 0.80,
                "VOL_SPIKE BUY " + String.format("%.0fx", volMult) + " vol"
                    + " body=" + String.format("%.0f%%", bodyPct * 100)
                    + " @" + close.setScale(2, RoundingMode.HALF_UP)
                    + " tgt=" + target + " rr=" + String.format("%.1f", rr),
                0.5, 0.3);
        } else {
            BigDecimal sl     = latest.high().multiply(BigDecimal.valueOf(1.002)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal target = close.multiply(BigDecimal.valueOf(0.99)).setScale(2, RoundingMode.HALF_UP);
            if (sl.compareTo(close) <= 0 || target.compareTo(close) >= 0) return null;
            double risk   = sl.subtract(close).doubleValue();
            double reward = close.subtract(target).doubleValue();
            double rr     = risk > 0 ? reward / risk : 0;
            if (rr < 1.5) return null;
            return new Signal(context.symbol(), Signal.Side.SELL, close, sl, target, 0.80,
                "VOL_SPIKE SELL " + String.format("%.0fx", volMult) + " vol"
                    + " body=" + String.format("%.0f%%", bodyPct * 100)
                    + " @" + close.setScale(2, RoundingMode.HALF_UP)
                    + " tgt=" + target + " rr=" + String.format("%.1f", rr),
                0.5, 0.3);
        }
    }
}
