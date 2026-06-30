package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Opening Gap Continuation — trades gaps that prove their strength by holding.
 *
 * Stocks that gap >0.5% at open and DON'T fill the gap during 9:15–9:29 are
 * under sustained directional pressure. The gap-hold signals institutional
 * conviction. Entry at 9:30 with SL at gap-fill level.
 *
 * Window:  9:30–9:35 IST (enters once the gap has held for 15 minutes)
 * Filter:  Gap >0.5% at open, gap NOT filled in first 15 candles (9:15–9:29)
 *          + volume in early candles is elevated (>2x avg)
 * Entry:   BUY for gap-up, SELL for gap-down
 * SL:      Gap fill level (prevDayClose) — but capped at 0.6% max risk
 * Target:  entry + gapSize × 1.5 (min 0.8%)
 * Trail:   activates at 0.5%, distance 0.3%
 */
@Slf4j
@Component
public class GapContinuationStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "GAP_CONTINUATION"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 5) return null;

        // Time window: 9:30–9:35 IST only
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour == null || istMinute == null) return null;
        int totalMin = istHour * 60 + istMinute;
        if (totalMin < 9 * 60 + 30 || totalMin > 9 * 60 + 35) return null;

        BigDecimal prevDayClose = context.extra("prevDayClose", BigDecimal.class);
        BigDecimal dayOpen      = context.extra("dayOpen",      BigDecimal.class);
        if (prevDayClose == null || dayOpen == null) return null;
        if (prevDayClose.compareTo(BigDecimal.ZERO) == 0) return null;

        Candle latest = context.getLatestCandle();
        if (latest == null) return null;

        BigDecimal close = latest.close();
        double px = close.doubleValue();
        if (px < 50 || px > 5000) return null;

        double prevClose = prevDayClose.doubleValue();
        double dayOpenD  = dayOpen.doubleValue();

        // Gap must be > 0.5%
        double gapPct = (dayOpenD - prevClose) / prevClose * 100.0;
        if (Math.abs(gapPct) < 0.5) return null;

        boolean gapUp   = gapPct > 0;
        boolean gapDown = gapPct < 0;

        // Entry must be within 0.4% of dayOpen (price hasn't chased too far)
        double distFromOpen = Math.abs(px - dayOpenD) / dayOpenD * 100.0;
        if (distFromOpen > 0.4) return null;

        // Verify gap held during 9:15–9:29: no candle CLOSED through prevDayClose
        LocalDate today = latest.timestamp().toLocalDate();
        for (Candle c : candles) {
            if (c.timestamp() == null) continue;
            if (!c.timestamp().toLocalDate().equals(today)) continue;
            int t = c.timestamp().getHour() * 60 + c.timestamp().getMinute();
            if (t < 9 * 60 + 15 || t >= 9 * 60 + 30) continue;
            // For gap up: close should stay above prevDayClose
            if (gapUp   && c.close().compareTo(prevDayClose) < 0) return null;
            // For gap down: close should stay below prevDayClose
            if (gapDown && c.close().compareTo(prevDayClose) > 0) return null;
        }

        // Volume: current candle >= 2x 10-period avg
        long volSum = 0;
        int volLen = Math.min(10, n - 1);
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        if (avgVol == 0) return null;
        double volMult = (double) latest.volume() / avgVol;
        if (volMult < 2.0) return null;

        // Body >= 60%
        BigDecimal candleRange = latest.high().subtract(latest.low());
        double bodyPct = 0;
        if (candleRange.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal body = close.subtract(latest.open()).abs();
            bodyPct = body.divide(candleRange, 4, RoundingMode.HALF_UP).doubleValue();
        }
        if (bodyPct < 0.60) return null;

        double gapSize = Math.abs(dayOpenD - prevClose); // ₹ gap size

        if (gapUp) {
            // BUY: gap-up continuation
            // SL: larger of (prevDayClose, entry × 0.994) — caps risk at 0.6%
            BigDecimal slAtGapFill = prevDayClose;
            BigDecimal slCap       = close.multiply(BigDecimal.valueOf(0.994)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal sl = slAtGapFill.compareTo(slCap) > 0
                ? slAtGapFill.setScale(2, RoundingMode.HALF_UP)
                : slCap;

            // Target: entry + gapSize × 1.5, min 0.8% above entry
            double targetD = Math.max(px * 1.008, px + gapSize * 1.5);
            BigDecimal target = BigDecimal.valueOf(targetD).setScale(2, RoundingMode.HALF_UP);

            double risk   = close.subtract(sl).doubleValue();
            double reward = target.subtract(close).doubleValue();
            if (risk <= 0) return null;
            double rr = reward / risk;
            if (rr < 1.5) return null;

            int score = scoreSignal(Math.abs(gapPct), volMult, bodyPct, distFromOpen, rr);
            if (score < 60) return null;

            return new Signal(
                context.symbol(), Signal.Side.BUY, close, sl, target, score / 100.0,
                "GAP_CONT BUY gap=" + String.format("+%.2f%%", gapPct)
                    + " held15min vol=" + String.format("%.1fx", volMult)
                    + " body=" + String.format("%.0f%%", bodyPct * 100)
                    + " score=" + score + "/100"
                    + " @" + close.setScale(2, RoundingMode.HALF_UP)
                    + " prevClose=" + prevDayClose.setScale(2, RoundingMode.HALF_UP)
                    + " sl=" + sl + " tgt=" + target + " rr=" + String.format("%.1f", rr),
                0.5, 0.3);
        }

        if (gapDown) {
            // SELL: gap-down continuation
            BigDecimal slAtGapFill = prevDayClose;
            BigDecimal slCap       = close.multiply(BigDecimal.valueOf(1.006)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal sl = slAtGapFill.compareTo(slCap) < 0
                ? slAtGapFill.setScale(2, RoundingMode.HALF_UP)
                : slCap;

            double targetD = Math.min(px * 0.992, px - gapSize * 1.5);
            BigDecimal target = BigDecimal.valueOf(targetD).setScale(2, RoundingMode.HALF_UP);

            double risk   = sl.subtract(close).doubleValue();
            double reward = close.subtract(target).doubleValue();
            if (risk <= 0) return null;
            double rr = reward / risk;
            if (rr < 1.5) return null;

            int score = scoreSignal(Math.abs(gapPct), volMult, bodyPct, distFromOpen, rr);
            if (score < 60) return null;

            return new Signal(
                context.symbol(), Signal.Side.SELL, close, sl, target, score / 100.0,
                "GAP_CONT SELL gap=" + String.format("%.2f%%", gapPct)
                    + " held15min vol=" + String.format("%.1fx", volMult)
                    + " body=" + String.format("%.0f%%", bodyPct * 100)
                    + " score=" + score + "/100"
                    + " @" + close.setScale(2, RoundingMode.HALF_UP)
                    + " prevClose=" + prevDayClose.setScale(2, RoundingMode.HALF_UP)
                    + " sl=" + sl + " tgt=" + target + " rr=" + String.format("%.1f", rr),
                0.5, 0.3);
        }

        return null;
    }

    private int scoreSignal(double gapPct, double volMult, double bodyPct, double distFromOpen, double rr) {
        int score = 0;
        // Gap size (0-25): larger gap = stronger institutional intent
        if      (gapPct >= 1.5) score += 25;
        else if (gapPct >= 1.0) score += 18;
        else if (gapPct >= 0.5) score += 10;
        // Volume (0-25)
        if      (volMult >= 4.0) score += 25;
        else if (volMult >= 3.0) score += 18;
        else if (volMult >= 2.0) score += 12;
        // Body % (0-20)
        if      (bodyPct >= 0.80) score += 20;
        else if (bodyPct >= 0.60) score += 12;
        // Proximity to open (0-15): entry close to open = less chasing
        if      (distFromOpen <= 0.10) score += 15;
        else if (distFromOpen <= 0.25) score += 10;
        else if (distFromOpen <= 0.40) score += 5;
        // RR (0-15)
        if      (rr >= 2.5) score += 15;
        else if (rr >= 2.0) score += 10;
        else if (rr >= 1.5) score += 5;
        return score;
    }
}
