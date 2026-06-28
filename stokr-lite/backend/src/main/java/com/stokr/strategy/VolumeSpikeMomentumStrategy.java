package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Volume Spike Momentum Strategy — intraday breakout on volume surge.
 *
 * BUY setup:
 *   - Current candle closes ABOVE the 15-min rolling high (fresh breakout)
 *   - Previous candle was still below that level (not already running)
 *   - Volume >= 2.5x 20-bar average (institutional footprint)
 *   - Bullish body >= 60% of candle range (clean breakout candle, no doji)
 *   - Close > VWAP (trend alignment)
 *   - Close > 20-EMA (medium-term trend)
 *   - RSI < 72 (not overbought)
 *   - SL: max(0.5 × ATR14, 0.3%) below entry (volatility-adjusted)
 *   - Target: 2:1 R:R from entry
 *   - Trail: activates at 1.2% gain, trails 0.6% from peak
 *
 * Window: 9:45–14:30 IST (skips ORB formation; avoids EOD volatility)
 */
@Slf4j
@Component
public class VolumeSpikeMomentumStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "VOLUME_SPIKE_MOMENTUM"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 25) return null;

        // Session window: 9:45–14:30 IST
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 9 * 60 + 45)  return null;
            if (min > 14 * 60 + 30) return null;
        }

        Candle curr = candles.get(n - 1);
        Candle prev = candles.get(n - 2);
        BigDecimal close = curr.close();

        // Must be a bullish candle
        if (close.compareTo(curr.open()) <= 0) return null;

        // Candle body quality >= 60% of range (filters doji / wick spikes)
        BigDecimal range = curr.high().subtract(curr.low());
        if (range.compareTo(BigDecimal.ZERO) <= 0) return null;
        BigDecimal body = close.subtract(curr.open()).abs();
        double bodyPct = body.divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        if (bodyPct < 0.60) return null;

        // 20-bar volume avg (excluding current candle)
        int volLen = Math.min(20, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;
        if (avgVol == 0) return null;

        double volMult = (double) curr.volume() / avgVol;
        if (volMult < 2.5) return null;

        // 15-min rolling high from the 15 candles BEFORE current
        int lookback = Math.min(15, n - 1);
        BigDecimal rollingHigh = BigDecimal.ZERO;
        for (int k = n - 1 - lookback; k < n - 1; k++) {
            if (candles.get(k).high().compareTo(rollingHigh) > 0)
                rollingHigh = candles.get(k).high();
        }
        // Fresh breakout: current close above, previous close was still below
        if (close.compareTo(rollingHigh) <= 0) return null;
        if (prev.close().compareTo(rollingHigh) > 0) return null;

        // VWAP alignment: must close above VWAP
        BigDecimal vwap = context.extra("vwap", BigDecimal.class);
        if (vwap != null && close.compareTo(vwap) <= 0) return null;

        // 20-EMA alignment: must close above 20-EMA
        double ema20 = computeEMA(candles, 20, n);
        if (close.doubleValue() <= ema20) return null;

        // RSI filter: skip overbought (>= 72)
        BigDecimal rsi14bd = context.extra("rsi14", BigDecimal.class);
        if (rsi14bd != null && rsi14bd.doubleValue() >= 72.0) return null;

        // ATR-based SL: max(0.5 × ATR14, 0.3%) below entry
        BigDecimal atr14 = context.extra("atr14", BigDecimal.class);
        double atrValue = atr14 != null ? atr14.doubleValue() : 0;
        double minSlDist = close.doubleValue() * 0.003;
        double slDistance = Math.max(0.5 * atrValue, minSlDist);
        BigDecimal sl = close.subtract(BigDecimal.valueOf(slDistance)).setScale(2, RoundingMode.HALF_UP);

        double risk = close.doubleValue() - sl.doubleValue();
        if (risk <= 0) return null;

        // Target: 2:1 R:R
        BigDecimal target = close.add(BigDecimal.valueOf(2.0 * risk)).setScale(2, RoundingMode.HALF_UP);
        double rrRatio = (target.doubleValue() - close.doubleValue()) / risk;
        if (rrRatio < 1.5) return null;

        // Confidence score
        int score = 0;
        if (volMult >= 4.0)       score += 35;
        else if (volMult >= 2.5)  score += 20;
        if (bodyPct >= 0.80)      score += 25;
        else if (bodyPct >= 0.60) score += 15;
        if (vwap != null && close.compareTo(vwap) > 0) score += 20;
        if (rsi14bd != null) {
            double rsi = rsi14bd.doubleValue();
            if      (rsi >= 55 && rsi < 65) score += 20;
            else if (rsi >= 50 && rsi < 72) score += 10;
        }

        log.debug("VSM signal: {} vol={:.1f}x body={:.0f}% score={}", context.symbol(), volMult, bodyPct * 100, score);

        return new Signal(
            context.symbol(), Signal.Side.BUY, close, sl, target,
            score / 100.0,
            "VSM_LONG @" + close.setScale(2, RoundingMode.HALF_UP)
                + " brk=" + rollingHigh.setScale(2, RoundingMode.HALF_UP)
                + " vol=" + String.format("%.1fx", volMult)
                + " body=" + String.format("%.0f%%", bodyPct * 100)
                + " sl=" + sl + " tgt=" + target
                + " rr=" + String.format("%.1f", rrRatio),
            1.2, 0.6);
    }

    private double computeEMA(List<Candle> candles, int period, int n) {
        int warmup = Math.min(period * 3, n - 1);
        int start = n - 1 - warmup;
        if (warmup < period) return candles.get(n - 1).close().doubleValue();
        double k = 2.0 / (period + 1);
        double ema = 0;
        for (int i = start; i < start + period; i++) ema += candles.get(i).close().doubleValue();
        ema /= period;
        for (int i = start + period; i < n; i++) ema = candles.get(i).close().doubleValue() * k + ema * (1 - k);
        return ema;
    }
}
