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
 *   Price pulls back to intraday VWAP, holds it as support, bounces.
 *   Entry is caught within 0–0.3% above VWAP (early bounce, not chasing).
 *   SL sits 0.1% below VWAP (natural invalidation).
 *   Target dynamically computed at 2:1 R:R from actual risk distance.
 *
 * All conditions (LONG):
 *   1. Time gate: IST 9:20–11:30 (let VWAP establish for first 5 min)
 *   2. Uptrend day: close > day open
 *   3. VWAP bounce: prev in [VWAP-0.3%, VWAP] (shallow touch), curr in [VWAP, VWAP+0.3%]
 *   4. Bullish candle: close > open, body ≥ 50% of range (decisive candle)
 *   5. Volume ≥ 1.5× 10-period average
 *   6. RSI 48–62 (confirmed upward momentum, not oversold rebound)
 *   7. Close > previous close
 *   SL: 0.1% below VWAP | Target: entry + 3 × (entry − SL) = 3:1 R:R (need only 25% win rate)
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
        Candle latest = context.getLatestCandle();
        Candle prev   = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        List<Candle> candles = context.candles();
        if (candles.size() < 15) return null;

        BigDecimal close = latest.close();
        BigDecimal vwap  = context.vwap();
        if (vwap == null || vwap.compareTo(BigDecimal.ZERO) == 0) return null;

        // 1. Time gate: IST 9:20–11:30 (skip first 5 candles; VWAP needs data to be meaningful)
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int totalMin = istHour * 60 + istMinute;
            if (totalMin < 9 * 60 + 20 || totalMin > 11 * 60 + 30) {
                log.debug("VWAP Bounce: outside time gate {}:{}", istHour, istMinute);
                return null;
            }
        }

        // 2. Uptrend day: close must be above the day's open price
        BigDecimal dayOpen = context.extra("dayOpen", BigDecimal.class);
        if (dayOpen != null && close.compareTo(dayOpen) <= 0) {
            log.debug("VWAP Bounce: not an uptrend day (close {} <= dayOpen {})", close, dayOpen);
            return null;
        }

        // 3. VWAP bounce zone: entry must be 0–0.3% above VWAP
        BigDecimal vwapCeiling = vwap.multiply(BigDecimal.valueOf(1.003));
        if (close.compareTo(vwap) <= 0 || close.compareTo(vwapCeiling) > 0) return null;

        // Previous candle must have touched VWAP but NOT broken more than 0.3% below it
        // (shallow pullback = healthy bounce; deep crash through VWAP = breakdown, not bounce)
        BigDecimal vwapFloor = vwap.multiply(BigDecimal.valueOf(0.997));
        if (prev.close().compareTo(vwap) > 0) return null;           // no touch — skip
        if (prev.close().compareTo(vwapFloor) < 0) return null;      // too deep below VWAP — skip

        // 4. Bullish candle with strong body (≥50% of range — decisive, not a doji)
        if (close.compareTo(latest.open()) <= 0) return null;
        BigDecimal range = latest.high().subtract(latest.low());
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal bodyPct = close.subtract(latest.open()).divide(range, 4, RoundingMode.HALF_UP);
            if (bodyPct.doubleValue() < 0.50) return null;
        }

        // 5. Volume ≥ 1.5× 10-period average
        int n = candles.size();
        long volSum = 0;
        int  volLen = Math.min(10, n);
        for (int k = n - volLen; k < n; k++) volSum += candles.get(k).volume();
        long avgVol = volSum / volLen;
        if (avgVol == 0 || latest.volume() < avgVol * 1.5) return null;

        // 6. RSI 48–62 (confirmed upward momentum, not oversold rebound which often fails)
        BigDecimal rsi = context.indicators() != null ? context.indicators().get("RSI14") : null;
        if (rsi == null) rsi = context.extra("rsi14", BigDecimal.class);
        if (rsi == null || rsi.doubleValue() < 48 || rsi.doubleValue() > 62) return null;

        // 7. Momentum: close > previous close
        if (close.compareTo(prev.close()) <= 0) return null;

        // SL: 0.1% below VWAP — natural invalidation level
        BigDecimal sl = vwap.multiply(BigDecimal.valueOf(0.999)).setScale(2, RoundingMode.HALF_UP);
        if (sl.compareTo(close) >= 0) return null;

        // Target: 3:1 R:R — only 25% win rate needed to break even (vs 33% for 2:1)
        BigDecimal risk   = close.subtract(sl);
        BigDecimal target = close.add(risk.multiply(BigDecimal.valueOf(3))).setScale(2, RoundingMode.HALF_UP);

        return new Signal(context.symbol(), Signal.Side.BUY, close, sl, target,
                0.85,
                "VWAP Bounce @" + close.setScale(2, RoundingMode.HALF_UP)
                + " vwap=" + vwap.setScale(2, RoundingMode.HALF_UP)
                + " sl=" + sl
                + " tgt=" + target
                + " rsi=" + rsi.setScale(1, RoundingMode.HALF_UP)
                + " vol=" + String.format("%.1fx", (double) latest.volume() / avgVol));
    }
}
