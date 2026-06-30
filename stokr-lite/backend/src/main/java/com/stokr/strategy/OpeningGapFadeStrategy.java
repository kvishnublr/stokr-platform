package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Opening Gap Fade — enter immediately when stocks gap open, targeting the gap fill.
 *
 * 60–70% of intraday gaps in Indian markets fill within 2 hours. This strategy
 * enters at the open and exits when price returns to the previous day's close.
 *
 * GAP UP  (>0.8%) → SHORT at open, target = prev day close, SL = 1.5× gap above
 * GAP DOWN(>0.8%) → LONG  at open, target = prev day close, SL = 1.5× gap below
 * Skip gaps >3% (too large — less likely to fill same day)
 *
 * Entry window: 9:16–9:25 IST (enter early, before the gap starts to run further).
 * Trail: 0.4% distance after target hit (lock in if gap overshoots).
 */
@Slf4j
@Component
public class OpeningGapFadeStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "OPENING_GAP_FADE"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 2) return null;

        Candle latest = context.getLatestCandle();
        if (latest == null || latest.timestamp() == null) return null;

        // Entry window: 9:16–9:25 IST only — enter fresh in the gap
        int hour = latest.timestamp().getHour();
        int min  = latest.timestamp().getMinute();
        int totalMin = hour * 60 + min;
        if (totalMin < 9 * 60 + 16 || totalMin > 9 * 60 + 25) return null;

        BigDecimal prevDayClose = context.extra("prevDayClose", BigDecimal.class);
        BigDecimal dayOpen      = context.extra("dayOpen",      BigDecimal.class);
        if (prevDayClose == null || dayOpen == null) return null;
        if (prevDayClose.compareTo(BigDecimal.ZERO) == 0) return null;

        double gapPct = dayOpen.subtract(prevDayClose).doubleValue()
            / prevDayClose.doubleValue() * 100.0;

        BigDecimal close = latest.close();
        double px = close.doubleValue();
        if (px < 100 || px > 3000) return null;

        if (gapPct >= 0.8 && gapPct <= 3.0) {
            // GAP UP → SHORT: fade back to prev close
            BigDecimal target = prevDayClose.setScale(2, RoundingMode.HALF_UP);
            // SL: 1.5x gap above the open (if gap extends we cut it)
            double slPct = gapPct * 1.5 / 100.0;
            BigDecimal sl = dayOpen.multiply(BigDecimal.valueOf(1.0 + slPct)).setScale(2, RoundingMode.HALF_UP);

            if (target.compareTo(close) >= 0) return null; // already at/below target
            if (sl.compareTo(close) <= 0) return null;

            double risk   = sl.subtract(close).doubleValue();
            double reward = close.subtract(target).doubleValue();
            double rr     = risk > 0 ? reward / risk : 0;
            if (rr < 0.8) return null;

            return new Signal(context.symbol(), Signal.Side.SELL, close, sl, target, 0.65,
                "GAP_FADE SHORT gap=" + String.format("+%.2f%%", gapPct)
                    + " @" + close.setScale(2, RoundingMode.HALF_UP)
                    + " tgt=" + target + " rr=" + String.format("%.1f", rr),
                999.0, 0.4);

        } else if (gapPct <= -0.8 && gapPct >= -3.0) {
            // GAP DOWN → LONG: fade back to prev close
            BigDecimal target = prevDayClose.setScale(2, RoundingMode.HALF_UP);
            double slPct = Math.abs(gapPct) * 1.5 / 100.0;
            BigDecimal sl = dayOpen.multiply(BigDecimal.valueOf(1.0 - slPct)).setScale(2, RoundingMode.HALF_UP);

            if (target.compareTo(close) <= 0) return null; // already at/above target
            if (sl.compareTo(close) >= 0) return null;

            double risk   = close.subtract(sl).doubleValue();
            double reward = target.subtract(close).doubleValue();
            double rr     = risk > 0 ? reward / risk : 0;
            if (rr < 0.8) return null;

            return new Signal(context.symbol(), Signal.Side.BUY, close, sl, target, 0.65,
                "GAP_FADE LONG gap=" + String.format("%.2f%%", gapPct)
                    + " @" + close.setScale(2, RoundingMode.HALF_UP)
                    + " tgt=" + target + " rr=" + String.format("%.1f", rr),
                999.0, 0.4);
        }

        return null;
    }
}
