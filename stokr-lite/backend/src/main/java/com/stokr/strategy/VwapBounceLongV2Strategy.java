package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * VWAP Bounce Long V2 — improved version with tighter filters targeting 65%+ WR.
 *
 * V1 produced 684 trades at 43% WR — too noisy. V2 fixes this by requiring:
 *   - Actual close BELOW VWAP on prev candle (not just low touching it)
 *   - Stock must have broken ORB bullishly (above orbHigh) at some point today
 *   - Later start window (11:30 vs 10:00) — VWAP is more meaningful after enough volume
 *   - Stricter volume (2.5x), body (65%), RSI (42–60), wick (20%)
 *   - Positive gap day only
 *   - Tue/Wed/Thu only
 *
 * Entry:
 *   1. Tue/Wed/Thu only
 *   2. Window 11:30–13:30 IST
 *   3. Stock has closed above orbHigh at any point today (established bullish day)
 *   4. Previous candle's CLOSE was BELOW VWAP (genuine dip through VWAP, not just touch)
 *   5. Current candle closes above VWAP by ≥ 0.2% (confirmed reclaim)
 *   6. Current close is above orbHigh (price is in bullish territory)
 *   7. Bullish engulfing body: close > open, body ≥ 65%
 *   8. Upper wick ≤ 20%
 *   9. Volume ≥ 2.5x 20-bar avg (genuine institutional bounce)
 *   10. RSI 42–60 (dipped from strength, bouncing — not overbought)
 *   11. Gap > 0% (opened above previous close — trend day)
 *
 * SL: 0.3% below VWAP (if it breaks VWAP again, thesis is wrong)
 * Target: 1.5:1
 */
@Slf4j
@Component
public class VwapBounceLongV2Strategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "VWAP_BOUNCE_V2"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 30) return null;

        // Window: 11:30–13:30 IST
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 11 * 60 + 30) return null;
            if (min > 13 * 60 + 30) return null;
        }

        // Tue/Wed/Thu only
        java.time.LocalDateTime ts = candles.get(n - 1).timestamp();
        if (ts != null) {
            java.time.DayOfWeek dow = ts.getDayOfWeek();
            if (dow == java.time.DayOfWeek.MONDAY || dow == java.time.DayOfWeek.FRIDAY) return null;
        }

        BigDecimal vwapBD = context.extra("vwap", BigDecimal.class);
        if (vwapBD == null) return null;
        double vwap = vwapBD.doubleValue();
        if (vwap <= 0) return null;

        BigDecimal orbHigh = context.extra("orbHigh", BigDecimal.class);
        if (orbHigh == null) return null;

        Candle curr = candles.get(n - 1);
        Candle prev = candles.get(n - 2);
        BigDecimal close = curr.close();
        double closeD = close.doubleValue();

        // Current close must be above orbHigh (bullish territory)
        if (close.compareTo(orbHigh) < 0) return null;

        // Current candle must close above VWAP by ≥ 0.2% (confirmed reclaim)
        double aboveVwapPct = (closeD - vwap) / vwap;
        if (aboveVwapPct < 0.002) return null;

        // Previous candle's CLOSE must have been BELOW VWAP (genuine dip below, not just touch)
        if (prev.close().doubleValue() >= vwap) return null;

        // Stock must have closed above orbHigh at some point TODAY (not historical days)
        java.time.LocalDate today = candles.get(n - 1).timestamp().toLocalDate();
        boolean hadOrbBreakout = false;
        for (int k = n - 2; k >= 0; k--) {
            if (!candles.get(k).timestamp().toLocalDate().equals(today)) break;
            if (candles.get(k).close().compareTo(orbHigh) > 0) {
                hadOrbBreakout = true;
                break;
            }
        }
        if (!hadOrbBreakout) return null;

        // Bullish candle: close > open
        if (close.compareTo(curr.open()) <= 0) return null;
        BigDecimal range = curr.high().subtract(curr.low());
        if (range.compareTo(BigDecimal.ZERO) <= 0) return null;

        // Body ≥ 65%
        double bodyPct = close.subtract(curr.open()).divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        if (bodyPct < 0.65) return null;

        // Upper wick ≤ 20%
        double upperWick = curr.high().doubleValue() - closeD;
        if (upperWick / range.doubleValue() > 0.20) return null;

        // Volume ≥ 2.5x 20-bar avg
        int volLen = Math.min(20, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        if (avgVol == 0) return null;
        double volMult = (double) curr.volume() / avgVol;
        if (volMult < 2.5) return null;

        // RSI 42–60
        BigDecimal rsi14bd = context.extra("rsi14", BigDecimal.class);
        if (rsi14bd != null) {
            double rsi = rsi14bd.doubleValue();
            if (rsi < 42 || rsi > 60) return null;
        }

        // Gap > 0% (positive gap day — trend days are where VWAP bounces work)
        BigDecimal prevDayClose = context.extra("prevDayClose", BigDecimal.class);
        BigDecimal dayOpen      = context.extra("dayOpen",      BigDecimal.class);
        if (prevDayClose != null && dayOpen != null && prevDayClose.compareTo(BigDecimal.ZERO) > 0) {
            double gapPct = (dayOpen.doubleValue() - prevDayClose.doubleValue()) / prevDayClose.doubleValue();
            if (gapPct <= 0) return null;
        }

        // SL: 0.3% below VWAP
        BigDecimal vwapBig = BigDecimal.valueOf(vwap);
        BigDecimal sl = vwapBig.multiply(BigDecimal.valueOf(0.997)).setScale(2, RoundingMode.HALF_UP);
        double risk = closeD - sl.doubleValue();
        if (risk <= 0) return null;
        double riskPct = risk / closeD;
        if (riskPct > 0.012) return null;
        if (riskPct < 0.002) return null;

        // 1.5:1 target
        BigDecimal target = close.add(BigDecimal.valueOf(1.5 * risk)).setScale(2, RoundingMode.HALF_UP);

        log.debug("VBL2: {} vwap={} close={}% above prevClose={} vol={}x body={}% rsi={}",
            context.symbol(),
            String.format("%.2f", vwap),
            String.format("%.2f", aboveVwapPct * 100),
            String.format("%.2f", prev.close().doubleValue()),
            String.format("%.1f", volMult),
            String.format("%.0f", bodyPct * 100),
            rsi14bd != null ? String.format("%.0f", rsi14bd.doubleValue()) : "n/a");

        return new Signal(
            context.symbol(), Signal.Side.BUY, close, sl, target,
            0.70,
            "VBL2 @" + close.setScale(2, RoundingMode.HALF_UP)
                + " vwap=" + vwapBig.setScale(2, RoundingMode.HALF_UP)
                + " vol=" + String.format("%.1fx", volMult)
                + " sl=" + sl + " tgt=" + target,
            0.9, 0.4);
    }
}
