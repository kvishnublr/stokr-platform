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
        if (hour != null && minute != null) {
            int totalMin = hour * 60 + minute;
            // Wide window: 9:25–14:00
            if (totalMin < 9 * 60 + 25 || totalMin > 14 * 60) return null;
        }

        double deviationPct = close.subtract(vwap).divide(vwap, 6, RoundingMode.HALF_UP).doubleValue() * 100;

        // Deviation band: 0.4–4.0% (wide to capture more signals)
        boolean isLong = deviationPct <= -0.4 && deviationPct >= -4.0;
        boolean isShort = deviationPct >= 0.4 && deviationPct <= 4.0;
        if (!isLong && !isShort) return null;

        int volLen = Math.min(10, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        if (avgVol > 0 && latest.volume() < avgVol * 1.2) return null;

        BigDecimal rsi = context.indicators() != null ? context.indicators().get("RSI14") : null;
        if (rsi == null) rsi = context.extra("rsi14", BigDecimal.class);

        Signal.Side side;
        if (isLong) {
            // Long: RSI 20–60 (widened — allow more oversold dips toward VWAP)
            if (rsi != null && (rsi.doubleValue() < 20 || rsi.doubleValue() > 60)) return null;
            if (close.compareTo(prevClose) <= 0) return null;
            side = Signal.Side.BUY;
        } else {
            // Short: RSI 40–82 (widened — allow overbought pushes above VWAP)
            if (rsi != null && (rsi.doubleValue() < 40 || rsi.doubleValue() > 82)) return null;
            if (close.compareTo(prevClose) >= 0) return null;
            side = Signal.Side.SELL;
        }

        double bodyPct = 0;
        BigDecimal range = latest.high().subtract(latest.low());
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal body = close.subtract(latest.open()).abs();
            bodyPct = body.divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        }
        if (bodyPct < 0.30) return null;

        // SL anchored to VWAP (if price reverts back through VWAP, thesis is wrong)
        BigDecimal sl, target;
        double rRatio;
        if (side == Signal.Side.BUY) {
            sl = vwap.multiply(BigDecimal.valueOf(0.997)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal risk = close.subtract(sl);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return null;
            target = close.add(risk.multiply(BigDecimal.valueOf(1.4))).setScale(2, RoundingMode.HALF_UP);
            rRatio = 1.4;
        } else {
            sl = vwap.multiply(BigDecimal.valueOf(1.003)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal risk = sl.subtract(close);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return null;
            target = close.subtract(risk.multiply(BigDecimal.valueOf(1.4))).setScale(2, RoundingMode.HALF_UP);
            rRatio = 1.4;
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
