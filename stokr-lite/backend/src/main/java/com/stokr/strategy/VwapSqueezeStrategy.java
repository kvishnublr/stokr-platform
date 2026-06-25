package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * VWAP Squeeze Breakout Strategy.
 * Detects price compression within ±0.35% of VWAP over 4 candles,
 * then enters on the breakout candle that clears the range with 2× volume.
 */
@Slf4j
@Component
public class VwapSqueezeStrategy implements StrategyPlugin {

    private static final double SQUEEZE_BAND_PCT = 0.35; // ±0.35% around VWAP = squeeze

    @Override
    public String getStrategyType() {
        return "VWAP_SQUEEZE";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 15) return null;

        // Time gate: 9:45–13:00 IST
        Integer hour   = context.extra("istHour",   Integer.class);
        Integer minute = context.extra("istMinute", Integer.class);
        if (hour == null) return null;
        int istMins = hour * 60 + (minute != null ? minute : 0);
        if (istMins < 9 * 60 + 45 || istMins > 13 * 60) return null;

        BigDecimal vwap = context.extra("vwap", BigDecimal.class);
        if (vwap == null || vwap.compareTo(BigDecimal.ZERO) == 0) return null;

        double vwapD = vwap.doubleValue();

        // Check squeeze: last 4 candles all within ±SQUEEZE_BAND_PCT of VWAP
        int squeezeCount = 0;
        double squeezeHigh = vwapD, squeezeLow = vwapD;
        for (int i = n - 5; i < n - 1; i++) {
            Candle c = candles.get(i);
            double hi = c.high().doubleValue(), lo = c.low().doubleValue();
            double hiDev = Math.abs(hi - vwapD) / vwapD * 100;
            double loDev = Math.abs(lo - vwapD) / vwapD * 100;
            if (hiDev <= SQUEEZE_BAND_PCT && loDev <= SQUEEZE_BAND_PCT) {
                squeezeCount++;
                squeezeHigh = Math.max(squeezeHigh, hi);
                squeezeLow  = Math.min(squeezeLow, lo);
            }
        }

        if (squeezeCount < 3) {
            log.debug("VWAP_SQUEEZE: only {} squeeze candles (need 3)", squeezeCount);
            return null;
        }

        Candle breakout = candles.get(n - 1);
        double close = breakout.close().doubleValue();

        // Breakout: close > squeezeHigh (above the compression zone)
        if (close <= squeezeHigh) {
            log.debug("VWAP_SQUEEZE: close {} not above squeezeHigh {}", close, squeezeHigh);
            return null;
        }

        // Breakout candle is bullish
        if (breakout.close().compareTo(breakout.open()) <= 0) return null;

        // Volume ≥ 2× 10-period average
        long avgVol = candles.subList(n - 10, n).stream().mapToLong(Candle::volume).sum() / 10;
        if (avgVol == 0 || breakout.volume() < avgVol * 2.0) {
            log.debug("VWAP_SQUEEZE: volume {} < 2x avg {}", breakout.volume(), avgVol);
            return null;
        }

        // ATR check
        BigDecimal atr = context.extra("atr14", BigDecimal.class);
        if (atr == null && context.indicators() != null) atr = context.indicators().get("ATR14");
        if (atr != null) {
            double atrPct = atr.divide(breakout.close(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
            if (atrPct < 0.05) return null;
        }

        // SL = VWAP - 0.3%, Target = entry + 1.5× squeeze range
        BigDecimal slPrice   = vwap.multiply(BigDecimal.valueOf(0.997));
        double squeezeRange  = squeezeHigh - squeezeLow;
        BigDecimal targetPrice = breakout.close().add(BigDecimal.valueOf(squeezeRange * 1.5));

        // If squeeze range is tiny fall back to params
        if (squeezeRange < vwapD * 0.001) {
            slPrice    = params.getStopLossPrice(breakout.close(), Signal.Side.BUY);
            targetPrice = params.getTargetPrice(breakout.close(), Signal.Side.BUY);
        }

        return new Signal(context.symbol(), Signal.Side.BUY, breakout.close(), slPrice, targetPrice,
                0.75, String.format("VWAP_SQUEEZE break=%.2f sqHi=%.2f vol=%d/%d",
                        close, squeezeHigh, breakout.volume(), avgVol),
                0.6, 0.35);
    }
}
