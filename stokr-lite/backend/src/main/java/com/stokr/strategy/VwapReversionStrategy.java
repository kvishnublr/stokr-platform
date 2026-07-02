package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Slf4j
@Component
public class VwapReversionStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "VWAP_REVERSION";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 16) return null;

        // Use extras map (consistent with all other VWAP strategies)
        BigDecimal vwap = context.extra("vwap", BigDecimal.class);
        if (vwap == null || vwap.compareTo(BigDecimal.ZERO) == 0) return null;

        Candle latest = candles.get(n - 1);
        Candle prev = candles.get(n - 2);
        BigDecimal close = latest.close();
        BigDecimal prevClose = prev.close();

        Integer hour = context.extra("istHour", Integer.class);
        Integer minute = context.extra("istMinute", Integer.class);
        if (hour == null || minute == null) return null;
        int totalMin = hour * 60 + minute;
        // Wide window: 9:25–14:00
        if (totalMin < 9 * 60 + 25 || totalMin > 14 * 60) return null;

        double deviationPct = close.subtract(vwap).divide(vwap, 6, RoundingMode.HALF_UP).doubleValue() * 100;

        // Deviation band: 0.4–4.0% (wide to capture more signals)
        boolean isLong = deviationPct <= -0.4 && deviationPct >= -4.0;
        boolean isShort = deviationPct >= 0.4 && deviationPct <= 4.0;
        if (!isLong && !isShort) return null;

        int volLen = Math.min(10, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        if (avgVol > 0 && latest.volume() < avgVol * 1.5) return null;

        BigDecimal rsi = context.indicators() != null ? context.indicators().get("RSI14") : null;
        if (rsi == null) rsi = context.extra("rsi14", BigDecimal.class);

        Signal.Side side;
        if (isLong) {
            // Long: RSI 25–52 (tightened — price below VWAP with mild oversold/neutral RSI)
            if (rsi != null && (rsi.doubleValue() < 25 || rsi.doubleValue() > 52)) return null;
            if (close.compareTo(prevClose) <= 0) return null;
            side = Signal.Side.BUY;
        } else {
            // Short: RSI 48–78 (tightened — price above VWAP with mild overbought/neutral RSI)
            if (rsi != null && (rsi.doubleValue() < 48 || rsi.doubleValue() > 78)) return null;
            if (close.compareTo(prevClose) >= 0) return null;
            side = Signal.Side.SELL;
        }

        double bodyPct = 0;
        BigDecimal range = latest.high().subtract(latest.low());
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal body = close.subtract(latest.open()).abs();
            bodyPct = body.divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        }
        if (bodyPct < 0.40) return null;  // tighter body — cleaner reversion candle

        // For LONG: close < VWAP (price is below VWAP, expect reversion up to VWAP)
        //   SL = 0.7% below close (tight stop), Target = VWAP (the mean-reversion point)
        //   Require deviation >= 1.0% so RR >= 1.0/0.7 ≈ 1.4
        // For SHORT: close > VWAP (price is above VWAP, expect reversion down to VWAP)
        //   SL = 0.7% above close, Target = VWAP
        if (Math.abs(deviationPct) < 1.0) return null;  // RR gate: need 1%+ deviation for 1.4+ RR

        BigDecimal sl, target;
        double rRatio;
        if (side == Signal.Side.BUY) {
            sl = close.multiply(BigDecimal.valueOf(0.993)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal risk = close.subtract(sl);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return null;
            // Target at VWAP + small overshoot buffer
            target = vwap.multiply(BigDecimal.valueOf(1.001)).setScale(2, RoundingMode.HALF_UP);
            double reward = target.subtract(close).doubleValue();
            if (reward <= 0) return null;
            rRatio = reward / risk.doubleValue();
            if (rRatio < 1.0) return null;
        } else {
            sl = close.multiply(BigDecimal.valueOf(1.007)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal risk = sl.subtract(close);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return null;
            // Target at VWAP - small undershoot buffer
            target = vwap.multiply(BigDecimal.valueOf(0.999)).setScale(2, RoundingMode.HALF_UP);
            double reward = close.subtract(target).doubleValue();
            if (reward <= 0) return null;
            rRatio = reward / risk.doubleValue();
            if (rRatio < 1.0) return null;
        }

        double confidence = 0.75 + (Math.abs(deviationPct) / 10.0) * 0.15;
        confidence = Math.min(confidence, 0.92);

        double trailTrigger = 1.0;
        double trailDistance = 0.4;

        return new Signal(context.symbol(), side, close, sl, target,
                confidence,
                (side == Signal.Side.BUY ? "VWAP Reversion Long" : "VWAP Reversion Short")
                + " @" + close.setScale(2, RoundingMode.HALF_UP)
                + " vwap=" + vwap.setScale(2, RoundingMode.HALF_UP)
                + " dev=" + String.format("%.2f%%", deviationPct)
                + " sl=" + sl + " tgt=" + target
                + " rsi=" + (rsi != null ? rsi.setScale(1, RoundingMode.HALF_UP).toString() : "N/A")
                + " R:R=1:" + String.format("%.1f", rRatio)
                + " vol=" + String.format("%.1fx", (double) latest.volume() / avgVol),
                trailTrigger, trailDistance);
    }
}
