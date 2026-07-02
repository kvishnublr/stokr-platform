package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * VWAP Bounce Long — buy bullish stocks that pull back to VWAP and bounce.
 *
 * VWAP is the strongest intraday support/resistance. When a bullish stock (above VWAP
 * earlier in the session) dips back to VWAP and bounces, it's a high-probability long.
 *
 * Entry conditions:
 *   1. Stock was above VWAP at some point in the last 10 candles (bullish bias)
 *   2. Previous candle's low touched VWAP ± 0.3% (genuine retest of VWAP)
 *   3. Current candle closes above VWAP by ≥ 0.15% (confirmed bounce)
 *   4. Current candle is bullish, body ≥ 50%
 *   5. Volume ≥ 1.5x 20-bar avg
 *   6. RSI 35–65 (oversold bounce, not overbought chase)
 *
 * SL: 0.3% below VWAP (if it breaks VWAP on next candle, trade is wrong)
 * Target: 1.5x risk (fixed — VWAP bounces are mean-reversion, take profit quickly)
 * Window: 10:00–13:30 IST
 *
 * Expected WR: 58–68% (VWAP is reliable support in trending stocks)
 */
@Slf4j
@Component
public class VwapBounceLongStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "VWAP_BOUNCE_LONG"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 22) return null;

        // Window: 10:00–13:30 IST
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour == null || istMinute == null) return null;
        int min = istHour * 60 + istMinute;
        if (min < 10 * 60)       return null;
        if (min > 13 * 60 + 30) return null;

        BigDecimal vwapBD = context.extra("vwap", BigDecimal.class);
        if (vwapBD == null) return null;
        double vwap = vwapBD.doubleValue();
        if (vwap <= 0) return null;

        Candle curr = candles.get(n - 1);
        Candle prev = candles.get(n - 2);
        BigDecimal close = curr.close();
        double closeD = close.doubleValue();

        // Current candle must close above VWAP by at least 0.15%
        double aboveVwapPct = (closeD - vwap) / vwap;
        if (aboveVwapPct < 0.0015) return null;

        // Current candle must be bullish, body >= 50%
        if (close.compareTo(curr.open()) <= 0) return null;
        BigDecimal range = curr.high().subtract(curr.low());
        if (range.compareTo(BigDecimal.ZERO) <= 0) return null;
        double bodyPct = close.subtract(curr.open()).divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        if (bodyPct < 0.50) return null;

        // Previous candle must have touched VWAP zone (low within 0.3% of VWAP)
        double prevLow = prev.low().doubleValue();
        if (prevLow > vwap * 1.003 || prevLow < vwap * 0.994) return null;

        // Stock must have been ABOVE VWAP in last 10 candles (bullish bias — not a downtrend)
        boolean wasAboveVwap = false;
        int checkBack = Math.min(10, n - 2);
        for (int k = n - 2 - checkBack; k < n - 2; k++) {
            if (candles.get(k).close().doubleValue() > vwap * 1.002) {
                wasAboveVwap = true;
                break;
            }
        }
        if (!wasAboveVwap) return null;

        // Previous candle must have closed below VWAP or very close to it (actual dip to VWAP)
        if (prev.close().doubleValue() > vwap * 1.003) return null;

        // Must be above orbHigh (bullish day structure — not trading a downtrend stock)
        BigDecimal orbHigh = context.extra("orbHigh", BigDecimal.class);
        if (orbHigh != null && close.compareTo(orbHigh) < 0) return null;

        // Volume >= 2.0x 20-bar avg (genuine bounce needs above-avg volume)
        int volLen = Math.min(20, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        double volMult = avgVol > 0 ? (double) curr.volume() / avgVol : 0;
        if (volMult < 2.0) return null;

        // RSI: genuine dip zone (38–60) — dipped but not crashed, not overbought
        BigDecimal rsi14bd = context.extra("rsi14", BigDecimal.class);
        if (rsi14bd != null) {
            double rsi = rsi14bd.doubleValue();
            if (rsi < 35 || rsi > 62) return null;
        }

        // SL: 0.3% below VWAP
        BigDecimal vwapBig = BigDecimal.valueOf(vwap);
        BigDecimal sl = vwapBig.multiply(BigDecimal.valueOf(0.997)).setScale(2, RoundingMode.HALF_UP);
        double risk = closeD - sl.doubleValue();
        if (risk <= 0) return null;

        double riskPct = risk / closeD;
        if (riskPct > 0.012) return null;  // don't trade if too far from VWAP
        if (riskPct < 0.002) return null;

        // Fixed 1.5:1 target — VWAP bounces are quick mean-reversion, not multi-percent moves
        BigDecimal target = close.add(BigDecimal.valueOf(1.5 * risk)).setScale(2, RoundingMode.HALF_UP);

        log.debug("VBL: {} vwap={} close={}% above vol={}x body={}% rsi={}",
            context.symbol(), String.format("%.2f", vwap),
            String.format("%.2f", aboveVwapPct * 100), String.format("%.1f", volMult),
            String.format("%.0f", bodyPct * 100),
            rsi14bd != null ? String.format("%.0f", rsi14bd.doubleValue()) : "n/a");

        return new Signal(
            context.symbol(), Signal.Side.BUY, close, sl, target,
            0.68,
            "VBL @" + close.setScale(2, RoundingMode.HALF_UP)
                + " vwap=" + vwapBig.setScale(2, RoundingMode.HALF_UP)
                + " vol=" + String.format("%.1fx", volMult)
                + " sl=" + sl + " tgt=" + target,
            0.8, 0.4);  // trail: activate at 0.8%, trail 0.4%
    }
}
