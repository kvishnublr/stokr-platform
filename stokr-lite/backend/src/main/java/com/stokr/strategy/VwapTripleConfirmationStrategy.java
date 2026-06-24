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
 *   3. VWAP bounce: prev close ≤ VWAP, curr close in [VWAP, VWAP+0.3%]
 *   4. Bullish candle: close > open, body ≥ 40% of range
 *   5. Volume ≥ 1.5× 10-period average
 *   6. RSI 42–65
 *   7. Close > previous close
 *   SL: 0.1% below VWAP | Target: entry + 2 × (entry − SL) = 2:1 R:R
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
        if (close.compareTo(vwap) <= 0 || close.compareTo(vwapCeiling) > 0) {
            log.debug("VWAP Bounce: close {} not in bounce zone [{}, {}]", close, vwap, vwapCeiling);
            return null;
        }
        // Previous candle must have been AT or BELOW VWAP (confirming the touch)
        if (prev.close().compareTo(vwap) > 0) {
            log.debug("VWAP Bounce: prev close {} > VWAP {} — no touch", prev.close(), vwap);
            return null;
        }

        // 4. Bullish candle with solid body
        if (close.compareTo(latest.open()) <= 0) {
            log.debug("VWAP Bounce: bearish candle");
            return null;
        }
        BigDecimal range = latest.high().subtract(latest.low());
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal bodyPct = close.subtract(latest.open()).divide(range, 4, RoundingMode.HALF_UP);
            if (bodyPct.doubleValue() < 0.40) {
                log.debug("VWAP Bounce: weak body {}%", bodyPct.multiply(BigDecimal.valueOf(100)).toPlainString());
                return null;
            }
        }

        // 5. Volume ≥ 1.5× 10-period average
        int n = candles.size();
        long volSum = 0;
        int  volLen = Math.min(10, n);
        for (int k = n - volLen; k < n; k++) volSum += candles.get(k).volume();
        long avgVol = volSum / volLen;
        if (avgVol == 0 || latest.volume() < avgVol * 1.5) {
            log.debug("VWAP Bounce: volume {} < 1.5x avg {}", latest.volume(), avgVol);
            return null;
        }

        // 6. RSI 42–65
        BigDecimal rsi = context.indicators() != null ? context.indicators().get("RSI14") : null;
        if (rsi == null) rsi = context.extra("rsi14", BigDecimal.class);
        if (rsi == null || rsi.doubleValue() < 42 || rsi.doubleValue() > 65) {
            log.debug("VWAP Bounce: RSI {} outside [42,65]", rsi);
            return null;
        }

        // 7. Momentum: close > previous close
        if (close.compareTo(prev.close()) <= 0) {
            log.debug("VWAP Bounce: close {} <= prev close {}", close, prev.close());
            return null;
        }

        // SL: 0.1% below VWAP — natural invalidation level
        BigDecimal sl = vwap.multiply(BigDecimal.valueOf(0.999)).setScale(2, RoundingMode.HALF_UP);
        if (sl.compareTo(close) >= 0) return null; // SL must be below entry

        // Target: 2:1 R:R from actual risk distance
        BigDecimal risk   = close.subtract(sl);
        BigDecimal target = close.add(risk.multiply(BigDecimal.valueOf(2))).setScale(2, RoundingMode.HALF_UP);

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
