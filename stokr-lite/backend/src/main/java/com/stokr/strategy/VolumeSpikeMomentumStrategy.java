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
 *   - Current candle closes ABOVE the 15-min rolling high by at least 0.3% (meaningful breakout)
 *   - Previous candle was still below that level (fresh, not already running)
 *   - Volume >= 4.0x 20-bar average (strong institutional footprint)
 *   - Bullish body >= 75% of candle range (strong clean candle)
 *   - Candle range > 1.2x ATR14 (expanded range = real momentum)
 *   - Close > VWAP (trend alignment)
 *   - Close > 20-EMA (medium-term trend)
 *   - RSI between 45–65 (sweet spot: momentum but not overbought)
 *   - SL: max(0.5 × ATR14, 0.3%) below entry (volatility-adjusted)
 *   - Target: 2:1 R:R from entry
 *   - Trail: activates at 1.2% gain, trails 0.6% from peak
 *
 * Window: 9:45–12:30 IST (prime momentum window; avoids lunch chop and EOD)
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

        // Session window: 9:45–12:30 IST (prime momentum window)
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 9 * 60 + 45)  return null;
            if (min > 12 * 60 + 30) return null;
        }

        Candle curr = candles.get(n - 1);
        Candle prev = candles.get(n - 2);
        BigDecimal close = curr.close();

        // Must be a bullish candle
        if (close.compareTo(curr.open()) <= 0) return null;

        // Candle body quality >= 75% (strong clean candle, no doji / wick spikes)
        BigDecimal range = curr.high().subtract(curr.low());
        if (range.compareTo(BigDecimal.ZERO) <= 0) return null;
        BigDecimal body = close.subtract(curr.open()).abs();
        double bodyPct = body.divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        if (bodyPct < 0.75) return null;

        // 20-bar volume avg (excluding current candle)
        int volLen = Math.min(20, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;
        if (avgVol == 0) return null;

        // Volume >= 4x avg (strong institutional footprint)
        double volMult = (double) curr.volume() / avgVol;
        if (volMult < 4.0) return null;

        // ATR check: candle range must exceed 1.2x ATR14 (expanded range = real momentum)
        BigDecimal atr14 = context.extra("atr14", BigDecimal.class);
        double atrValue = atr14 != null ? atr14.doubleValue() : 0;
        if (atrValue > 0 && range.doubleValue() < atrValue * 1.2) return null;

        // 15-min rolling high from the 15 candles BEFORE current
        int lookback = Math.min(15, n - 1);
        BigDecimal rollingHigh = BigDecimal.ZERO;
        for (int k = n - 1 - lookback; k < n - 1; k++) {
            if (candles.get(k).high().compareTo(rollingHigh) > 0)
                rollingHigh = candles.get(k).high();
        }
        // Fresh breakout: current close above rollingHigh by >= 0.3% (meaningful, not micro)
        double breakoutPct = (close.doubleValue() - rollingHigh.doubleValue()) / rollingHigh.doubleValue();
        if (breakoutPct < 0.003) return null;
        // Previous close must have been at or below rollingHigh (fresh, not already running)
        if (prev.close().compareTo(rollingHigh) > 0) return null;

        // VWAP alignment: must close above VWAP
        BigDecimal vwap = context.extra("vwap", BigDecimal.class);
        if (vwap != null && close.compareTo(vwap) <= 0) return null;

        // 20-EMA alignment: must close above 20-EMA
        double ema20 = computeEMA(candles, 20, n);
        if (close.doubleValue() <= ema20) return null;

        // RSI sweet spot: 45–65 (momentum building, not yet overbought)
        BigDecimal rsi14bd = context.extra("rsi14", BigDecimal.class);
        if (rsi14bd != null) {
            double rsi = rsi14bd.doubleValue();
            if (rsi < 45.0 || rsi > 65.0) return null;
        }

        // ATR-based SL: max(0.5 × ATR14, 0.3%) below entry
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
        if (volMult >= 6.0)       score += 35;
        else if (volMult >= 4.0)  score += 25;
        if (bodyPct >= 0.85)      score += 25;
        else if (bodyPct >= 0.75) score += 15;
        if (vwap != null && close.compareTo(vwap) > 0) score += 20;
        if (breakoutPct >= 0.006) score += 20;
        else if (breakoutPct >= 0.003) score += 10;

        log.debug("VSM: {} vol={:.1f}x body={:.0f}% brk={:.2f}% score={}", context.symbol(), volMult, bodyPct * 100, breakoutPct * 100, score);

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
