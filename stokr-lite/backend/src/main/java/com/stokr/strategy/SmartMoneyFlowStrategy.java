package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Smart Money Flow Strategy — detects volume climax + failed follow-through.
 *
 * The market wisdom behind this:
 *   Smart money (institutions) distributes/accumulates position during high-volume moves.
 *   When price breaks a key level (ORB high/low) with 3×+ average volume,
 *   but the NEXT candle fails to follow through, it signals the move is exhausted.
 *   Retail traders who chased the breakout get trapped → reversal.
 *
 * Setup (SHORT):
 *   - Candle i-1 breaks above ORB high with volume ≥ 3× 10-candle avg
 *   - Candle i closes back below candle i-1's close (failed follow-through)
 *   - SL at candle i-1's high × 1.003 (min 0.5% from entry) | Target = ORB range × 1.0 from entry
 *
 * Setup (LONG):
 *   - Candle i-1 breaks below ORB low with volume ≥ 3× 10-candle avg
 *   - Candle i closes back above candle i-1's close (failed follow-through)
 *   - SL at candle i-1's low × 0.997 (min 0.5% from entry) | Target = ORB range × 1.0 from entry
 */
@Slf4j
@Component
public class SmartMoneyFlowStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "SMART_MONEY_FLOW";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 18) return null;

        BigDecimal orbHigh  = context.extra("orbHigh",  BigDecimal.class);
        BigDecimal orbLow   = context.extra("orbLow",   BigDecimal.class);
        BigDecimal orbRange = context.extra("orbRange",  BigDecimal.class);
        if (orbHigh == null || orbLow == null || orbRange == null) return null;
        if (orbRange.compareTo(BigDecimal.valueOf(0.01)) < 0) return null;

        // Morning window only: 9:30–11:00 IST
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 9 * 60 + 30 || min > 11 * 60 + 0) return null;
        }

        Candle curr = candles.get(n - 1);
        Candle prev = candles.get(n - 2);
        BigDecimal close = curr.close();
        BigDecimal prevClose = prev.close();

        // Calculate 10-period average volume (excluding prev candle to avoid self-reference)
        int volLen = Math.min(10, n - 2);
        long volSum = 0;
        for (int k = n - 2 - volLen; k < n - 2; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;

        // SHORT setup: prev broke above ORB high with climax volume, curr failed to follow
        if (prev.high().compareTo(orbHigh) > 0 && avgVol > 0 && prev.volume() >= avgVol * 3) {
            if (close.compareTo(prevClose) <= 0) {
                // 0.3% buffer above prev high; floor at 0.5% from entry to survive 1-min noise
                BigDecimal sl = prev.high().multiply(BigDecimal.valueOf(1.003))
                    .setScale(2, RoundingMode.HALF_UP);
                double minSlDist = close.doubleValue() * 0.005;
                if (sl.subtract(close).doubleValue() < minSlDist)
                    sl = close.add(BigDecimal.valueOf(minSlDist)).setScale(2, RoundingMode.HALF_UP);

                BigDecimal target = orbLow.subtract(orbRange)
                    .setScale(2, RoundingMode.HALF_UP);

                if (target.compareTo(close) < 0 && sl.compareTo(close) > 0) {
                    double risk = sl.subtract(close).doubleValue();
                    double reward = close.subtract(target).doubleValue();
                    double rr = risk > 0 ? reward / risk : 0;
                    if (rr < 1.0) return null;

                    double pctDist = risk / close.doubleValue() * 100.0;
                    double trailTrigger = Math.max(0.8, pctDist * 0.8);
                    double trailDistance = Math.max(0.3, pctDist * 0.4);

                    return new Signal(context.symbol(), Signal.Side.SELL, close, sl, target,
                        0.75,
                        "SMF_SHORT @" + close.setScale(2, RoundingMode.HALF_UP)
                            + " orb=[" + orbLow + "-" + orbHigh + "]"
                            + " prevVol=" + String.format("%.1fx", (double) prev.volume() / avgVol)
                            + " rr=" + String.format("%.1f", rr),
                        trailTrigger, trailDistance);
                }
            }
        }

        // LONG setup: prev broke below ORB low with climax volume, curr failed to follow
        if (prev.low().compareTo(orbLow) < 0 && avgVol > 0 && prev.volume() >= avgVol * 3) {
            if (close.compareTo(prevClose) >= 0) {
                // 0.3% buffer below prev low; floor at 0.5% from entry
                BigDecimal sl = prev.low().multiply(BigDecimal.valueOf(0.997))
                    .setScale(2, RoundingMode.HALF_UP);
                double minSlDist = close.doubleValue() * 0.005;
                if (close.subtract(sl).doubleValue() < minSlDist)
                    sl = close.subtract(BigDecimal.valueOf(minSlDist)).setScale(2, RoundingMode.HALF_UP);

                BigDecimal target = orbHigh.add(orbRange)
                    .setScale(2, RoundingMode.HALF_UP);

                if (target.compareTo(close) > 0 && sl.compareTo(close) < 0) {
                    // BUG FIX: risk = close - sl (positive) — was sl - close (negative → rr=0 → all LONGs rejected)
                    double risk = close.subtract(sl).doubleValue();
                    double reward = target.subtract(close).doubleValue();
                    double rr = risk > 0 ? reward / risk : 0;
                    if (rr < 1.0) return null;

                    double pctDist = risk / close.doubleValue() * 100.0;
                    double trailTrigger = Math.max(0.8, pctDist * 0.8);
                    double trailDistance = Math.max(0.3, pctDist * 0.4);

                    return new Signal(context.symbol(), Signal.Side.BUY, close, sl, target,
                        0.75,
                        "SMF_LONG @" + close.setScale(2, RoundingMode.HALF_UP)
                            + " orb=[" + orbLow + "-" + orbHigh + "]"
                            + " prevVol=" + String.format("%.1fx", (double) prev.volume() / avgVol)
                            + " rr=" + String.format("%.1f", rr),
                        trailTrigger, trailDistance);
                }
            }
        }

        return null;
    }
}
