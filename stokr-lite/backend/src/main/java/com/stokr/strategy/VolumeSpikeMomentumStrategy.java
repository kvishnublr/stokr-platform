package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Volume Spike Momentum Strategy — gap-and-run continuation on volume surge.
 *
 * Triple confirmation BUY setup:
 *   1. GAP: stock gapped up >= 0.3% at open (institutional pre-market conviction)
 *   2. ORB: close is already above orbHigh (confirmed bullish day structure)
 *   3. BREAKOUT: close breaks 15-min rolling high by >= 0.2% with volume >= 3.5x avg
 *
 * Additional quality filters:
 *   - Bullish body >= 70% of candle range
 *   - Candle range > 0.8x ATR14 (real momentum, not noise)
 *   - Close > VWAP
 *   - Close > 20-EMA
 *   - RSI < 70 (not overbought)
 *   - SL: max(0.5 × ATR14, 0.3%) below entry
 *   - Target: 2:1 R:R (profitable at 50%+ WR expected from triple confirm)
 *   - Trail: activates at 1.2% gain, trails 0.6% from peak
 *
 * Window: 10:00–13:30 IST (ORB must be formed; avoids first 30 min chaos)
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

        // Session window: 10:00–13:30 IST (ORB must be formed; prime continuation window)
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 10 * 60)       return null;
            if (min > 13 * 60 + 30) return null;
        }

        Candle curr = candles.get(n - 1);
        Candle prev = candles.get(n - 2);
        BigDecimal close = curr.close();

        // ── GATE 1: Gap-up confirmation ──────────────────────────────────────
        // Stock must have opened >= 0.3% above previous close (institutional pre-market conviction)
        BigDecimal prevDayClose = context.extra("prevDayClose", BigDecimal.class);
        BigDecimal dayOpen      = context.extra("dayOpen",      BigDecimal.class);
        if (prevDayClose != null && dayOpen != null && prevDayClose.compareTo(BigDecimal.ZERO) > 0) {
            double gapPct = (dayOpen.doubleValue() - prevDayClose.doubleValue()) / prevDayClose.doubleValue();
            if (gapPct < 0.003) return null;
        }

        // ── GATE 2: ORB confirmation ──────────────────────────────────────────
        // Close must be above orbHigh — confirmed bullish day structure
        BigDecimal orbHigh  = context.extra("orbHigh",  BigDecimal.class);
        BigDecimal orbLow   = context.extra("orbLow",   BigDecimal.class);
        BigDecimal orbRange = context.extra("orbRange", BigDecimal.class);
        if (orbHigh == null || orbLow == null) return null;
        if (close.compareTo(orbHigh) <= 0) return null;

        // Must be a bullish candle
        if (close.compareTo(curr.open()) <= 0) return null;

        // Candle body quality >= 70% (clean candle)
        BigDecimal range = curr.high().subtract(curr.low());
        if (range.compareTo(BigDecimal.ZERO) <= 0) return null;
        BigDecimal body = close.subtract(curr.open()).abs();
        double bodyPct = body.divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        if (bodyPct < 0.70) return null;

        // 20-bar volume avg (excluding current candle)
        int volLen = Math.min(20, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;
        if (avgVol == 0) return null;

        // Volume >= 3.5x avg
        double volMult = (double) curr.volume() / avgVol;
        if (volMult < 3.5) return null;

        // ATR check: candle range must exceed 0.8x ATR14
        BigDecimal atr14 = context.extra("atr14", BigDecimal.class);
        double atrValue = atr14 != null ? atr14.doubleValue() : 0;
        if (atrValue > 0 && range.doubleValue() < atrValue * 0.8) return null;

        // ── GATE 3: 15-min rolling high breakout ─────────────────────────────
        int lookback = Math.min(15, n - 1);
        BigDecimal rollingHigh = BigDecimal.ZERO;
        for (int k = n - 1 - lookback; k < n - 1; k++) {
            if (candles.get(k).high().compareTo(rollingHigh) > 0)
                rollingHigh = candles.get(k).high();
        }
        // Must break rolling high by >= 0.2% (meaningful, not micro-breakout)
        double breakoutPct = rollingHigh.compareTo(BigDecimal.ZERO) > 0
            ? (close.doubleValue() - rollingHigh.doubleValue()) / rollingHigh.doubleValue()
            : 0;
        if (breakoutPct < 0.002) return null;
        // Previous candle must have been at or below rolling high (fresh breakout)
        if (prev.close().compareTo(rollingHigh) > 0) return null;

        // VWAP alignment
        BigDecimal vwap = context.extra("vwap", BigDecimal.class);
        if (vwap != null && close.compareTo(vwap) <= 0) return null;

        // 20-EMA alignment
        double ema20 = computeEMA(candles, 20, n);
        if (close.doubleValue() <= ema20) return null;

        // RSI: not overbought (< 70)
        BigDecimal rsi14bd = context.extra("rsi14", BigDecimal.class);
        if (rsi14bd != null && rsi14bd.doubleValue() >= 70.0) return null;

        // ATR-based SL: max(0.5 × ATR14, 0.3%) below entry
        double minSlDist = close.doubleValue() * 0.003;
        double slDistance = Math.max(0.5 * atrValue, minSlDist);
        BigDecimal sl = close.subtract(BigDecimal.valueOf(slDistance)).setScale(2, RoundingMode.HALF_UP);

        double risk = close.doubleValue() - sl.doubleValue();
        if (risk <= 0) return null;

        // Target: 2:1 R:R (profitable at 50%+ WR expected from triple confirmation)
        BigDecimal target = close.add(BigDecimal.valueOf(2.0 * risk)).setScale(2, RoundingMode.HALF_UP);
        double rrRatio = (target.doubleValue() - close.doubleValue()) / risk;
        if (rrRatio < 1.5) return null;

        // Confidence score
        int score = 0;
        if (volMult >= 6.0)            score += 30;
        else if (volMult >= 3.5)       score += 20;
        if (bodyPct >= 0.85)           score += 25;
        else if (bodyPct >= 0.70)      score += 15;
        if (vwap != null && close.compareTo(vwap) > 0) score += 20;
        if (breakoutPct >= 0.005)      score += 25;
        else if (breakoutPct >= 0.002) score += 15;

        log.debug("VSM: {} gap+orb+brk vol={} body={}% brk={}% score={}",
            context.symbol(), String.format("%.1fx", volMult),
            String.format("%.0f", bodyPct * 100), String.format("%.2f", breakoutPct * 100), score);

        return new Signal(
            context.symbol(), Signal.Side.BUY, close, sl, target,
            score / 100.0,
            "VSM_LONG @" + close.setScale(2, RoundingMode.HALF_UP)
                + " gap+orb+brk=" + rollingHigh.setScale(2, RoundingMode.HALF_UP)
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
