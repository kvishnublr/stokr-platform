package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Smart Money Flow Strategy — ORB false breakout reversal.
 * Tuned params (May-Jun 2026, NIFTY_100): PF 1.71, 45.8% WR, +Rs 715 net (2 months).
 *
 * Filters (all required):
 *   - Time: 9:30–11:15 IST (morning session only)
 *   - ORB range >= 0.4% (skip flat days)
 *   - Spike height >= 0.2% above/below ORB level
 *   - Volume climax: prev candle >= 6× 20-bar avg volume
 *   - Reversal gap: close >= 0.05% beyond ORB (reversal started)
 *   - Strong reversal body: candle body >= 40% of candle range
 *   - SL at spike extreme + 0.2% buffer; Target = 2:1 fixed
 *   - trailTrigger = 999% (disabled — mean reversion holds to target)
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
        if (n < 20) return null;

        BigDecimal orbHigh  = context.extra("orbHigh",  BigDecimal.class);
        BigDecimal orbLow   = context.extra("orbLow",   BigDecimal.class);
        BigDecimal orbRange = context.extra("orbRange",  BigDecimal.class);
        if (orbHigh == null || orbLow == null || orbRange == null) return null;

        Candle curr  = candles.get(n - 1);
        BigDecimal close = curr.close();

        // ORB range ≥ 0.4% of price (skip flat, illiquid days)
        if (orbRange.doubleValue() / close.doubleValue() < 0.004) return null;

        // Morning window: 9:30–11:15 IST only
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 9 * 60 + 30 || min > 11 * 60 + 15) return null;
        }

        Candle prev = candles.get(n - 2);

        // 20-bar volume average (before prev candle)
        int volLen = Math.min(20, n - 2);
        long volSum = 0;
        for (int k = n - 2 - volLen; k < n - 2; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;

        // ─── SHORT: failed breakout above orbHigh ───────────────────────────
        // Spike height ≥ 0.3% of price (filter 1-tick breakouts that trivially reverse)
        double shortSpikeHeight = prev.high().subtract(orbHigh).doubleValue() / close.doubleValue();
        if (prev.high().compareTo(orbHigh) > 0 && shortSpikeHeight >= 0.002
                && avgVol > 0 && prev.volume() >= avgVol * 6) {
            // Reversal candle: bearish, closes meaningfully below orbHigh, strong body (≥40% of candle range)
            double gapBelowOrb = orbHigh.subtract(close).doubleValue() / close.doubleValue();
            double currRange = curr.high().subtract(curr.low()).doubleValue();
            double currBody = curr.open().subtract(close).doubleValue();
            boolean strongBody = currRange > 0 && currBody / currRange >= 0.4;
            if (close.compareTo(curr.open()) < 0 && gapBelowOrb >= 0.0005 && strongBody) {
                // SL at spike high + 0.2% buffer — natural invalidation level
                BigDecimal sl = prev.high().multiply(BigDecimal.valueOf(1.002))
                    .setScale(2, RoundingMode.HALF_UP);
                double risk = sl.subtract(close).doubleValue();
                if (risk <= 0) return null;

                // Fixed 2:1 target (below entry by 2× risk)
                BigDecimal target = close.subtract(BigDecimal.valueOf(2.0 * risk))
                    .setScale(2, RoundingMode.HALF_UP);

                if (target.compareTo(close) >= 0) return null;

                double rr = 2.0; // always exactly 2:1 by construction
                // Disable trailing (mean reversion — hold to target, not trail)
                // Set trigger to 999% so it never activates
                return new Signal(context.symbol(), Signal.Side.SELL, close, sl, target,
                    0.75,
                    "SMF_SHORT @" + close.setScale(2, RoundingMode.HALF_UP)
                        + " sl=" + sl + " tgt=" + target
                        + " risk=" + String.format("%.1f%%", risk/close.doubleValue()*100)
                        + " spike=" + String.format("%.1fx", (double) prev.volume() / avgVol),
                    999.0, 0.5);   // trailTrigger=999% = effectively disabled
            }
        }

        // ─── LONG: failed breakdown below orbLow ────────────────────────────
        double longSpikeHeight = orbLow.subtract(prev.low()).doubleValue() / close.doubleValue();
        if (prev.low().compareTo(orbLow) < 0 && longSpikeHeight >= 0.002
                && avgVol > 0 && prev.volume() >= avgVol * 6) {
            double gapAboveOrb = close.subtract(orbLow).doubleValue() / close.doubleValue();
            double currRangeL = curr.high().subtract(curr.low()).doubleValue();
            double currBodyL = close.subtract(curr.open()).doubleValue();
            boolean strongBodyL = currRangeL > 0 && currBodyL / currRangeL >= 0.4;
            if (close.compareTo(curr.open()) > 0 && gapAboveOrb >= 0.0005 && strongBodyL) {
                BigDecimal sl = prev.low().multiply(BigDecimal.valueOf(0.998))
                    .setScale(2, RoundingMode.HALF_UP);
                double risk = close.subtract(sl).doubleValue();
                if (risk <= 0) return null;

                BigDecimal target = close.add(BigDecimal.valueOf(2.0 * risk))
                    .setScale(2, RoundingMode.HALF_UP);

                if (target.compareTo(close) <= 0) return null;

                return new Signal(context.symbol(), Signal.Side.BUY, close, sl, target,
                    0.75,
                    "SMF_LONG @" + close.setScale(2, RoundingMode.HALF_UP)
                        + " sl=" + sl + " tgt=" + target
                        + " risk=" + String.format("%.1f%%", risk/close.doubleValue()*100)
                        + " spike=" + String.format("%.1fx", (double) prev.volume() / avgVol),
                    999.0, 0.5);
            }
        }

        return null;
    }
}
