package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * VWAP Bounce Strategy — high-probability entries only.
 *
 * Entry logic:
 *   2-candle confirmation: touch candle dips to VWAP, bounce candle closes above it,
 *   entry candle also closes above it (= confirmed hold).
 *   SL sits 0.5% below VWAP (room for noise).
 *   Target dynamically computed at 2:1 R:R from actual risk distance.
 *
 * All conditions (LONG):
 *   1. Time gate: IST 9:45–12:00 (VWAP settled + enough runway for target)
 *   2. VWAP slope: current VWAP > VWAP 1-min ago AND > VWAP 5-min ago (rising momentum)
 *   3. 2-candle bounce: touch candle in [VWAP-0.3%, VWAP], bounce candle above VWAP,
 *      entry candle also above VWAP (0–0.3% zone)
 *   4. Bullish candle: close > open, body ≥ 60% of range
 *   5. Volume ≥ 1.5× 10-period average (prior candles only)
 *   6. RSI 53–63 (genuine upward momentum, not oversold rebound)
 *   7. Close > previous close
 *   SL: 0.5% below VWAP | Target: entry + 2×(entry − SL) = 2:1 R:R
 */
@Slf4j
@Component
public class VwapTripleConfirmationStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "VWAP_TRIPLE";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        if (candles.size() < 16) return null;

        int n = candles.size();
        Candle latest     = candles.get(n - 1);  // entry candle
        Candle prev       = candles.get(n - 2);  // first bounce candle
        Candle touchCandle = candles.get(n - 3); // VWAP touch candle

        BigDecimal close = latest.close();
        BigDecimal vwap  = context.vwap();
        if (vwap == null || vwap.compareTo(BigDecimal.ZERO) == 0) return null;

        // 1. Time gate: IST 9:45–12:00 (avoid opening VWAP instability + give time for target to hit)
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int totalMin = istHour * 60 + istMinute;
            if (totalMin < 9 * 60 + 45 || totalMin > 12 * 60 + 0) return null;
        }

        // 2. VWAP slope: current VWAP must be above VWAP 1-min ago AND 5-min ago (rising momentum)
        BigDecimal prevVwap1 = context.extra("prevVwap1", BigDecimal.class);
        BigDecimal prevVwap5 = context.extra("prevVwap5", BigDecimal.class);
        if (prevVwap5 != null && vwap.compareTo(prevVwap5) <= 0) {
            log.debug("VWAP Bounce: VWAP not rising over 5-min ({} <= {})", vwap, prevVwap5);
            return null;
        }
        if (prevVwap1 != null && vwap.compareTo(prevVwap1) <= 0) {
            log.debug("VWAP Bounce: VWAP not rising in last min ({} <= {})", vwap, prevVwap1);
            return null;
        }

        // 3. 2-candle bounce confirmation
        BigDecimal vwapCeiling = vwap.multiply(BigDecimal.valueOf(1.003));
        BigDecimal vwapFloor   = vwap.multiply(BigDecimal.valueOf(0.997));

        // touch candle: dipped to VWAP zone (shallow touch)
        if (touchCandle.close().compareTo(vwap) > 0)         return null; // no touch
        if (touchCandle.close().compareTo(vwapFloor) < 0)    return null; // crashed through — skip

        // bounce candle (prev): first close above VWAP
        if (prev.close().compareTo(vwap) <= 0)                return null; // didn't bounce yet
        if (prev.close().compareTo(vwapCeiling) > 0)          return null; // chasing too far

        // entry candle (latest): confirms hold above VWAP
        if (close.compareTo(vwap) <= 0 || close.compareTo(vwapCeiling) > 0) return null;

        // 4. Bullish candle with strong body (≥60% of range)
        if (close.compareTo(latest.open()) <= 0) return null;
        BigDecimal range = latest.high().subtract(latest.low());
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal bodyPct = close.subtract(latest.open()).divide(range, 4, RoundingMode.HALF_UP);
            if (bodyPct.doubleValue() < 0.60) return null;
        }

        // 5. Volume ≥ 1.5× 10-period average — exclude current candle from average
        int volLen = Math.min(10, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        long avgVol = volSum / volLen;
        if (avgVol == 0 || latest.volume() < avgVol * 1.5) return null;

        // 6. RSI 53–63: genuine upward momentum (not oversold rebound, not overstretched)
        BigDecimal rsi = context.indicators() != null ? context.indicators().get("RSI14") : null;
        if (rsi == null) rsi = context.extra("rsi14", BigDecimal.class);
        if (rsi == null || rsi.doubleValue() < 53 || rsi.doubleValue() > 63) return null;

        // 7. Momentum: close > previous close
        if (close.compareTo(prev.close()) <= 0) return null;

        // SL: 0.5% below VWAP — room for intraday noise
        BigDecimal sl = vwap.multiply(BigDecimal.valueOf(0.995)).setScale(2, RoundingMode.HALF_UP);
        if (sl.compareTo(close) >= 0) return null;

        // Target: 2:1 R:R
        BigDecimal risk   = close.subtract(sl);
        BigDecimal target = close.add(risk.multiply(BigDecimal.valueOf(2))).setScale(2, RoundingMode.HALF_UP);

        // Trailing stop: proportional to SL distance so it doesn't cut winners before target
        double slPct = risk.doubleValue() / close.doubleValue() * 100.0;
        double trailTrigger = Math.max(0.8, slPct * 1.2);   // activate after 1.2× SL distance
        double trailDistance = Math.max(0.3, slPct * 0.6);  // trail at 0.6× SL below peak

        return new Signal(context.symbol(), Signal.Side.BUY, close, sl, target,
                0.85,
                "VWAP Bounce @" + close.setScale(2, RoundingMode.HALF_UP)
                + " vwap=" + vwap.setScale(2, RoundingMode.HALF_UP)
                + " sl=" + sl
                + " tgt=" + target
                + " rsi=" + rsi.setScale(1, RoundingMode.HALF_UP)
                + " vol=" + String.format("%.1fx", (double) latest.volume() / avgVol),
                trailTrigger, trailDistance);
    }
}
