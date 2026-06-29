package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Intraday High Breakout — fires when a stock makes a new session high in the 11:30–13:00
 * window on days where the ORB already resolved bullish. Targets the second momentum leg.
 *
 * Rationale: ORB captures the first breakout (10:00–13:00). IHB captures continuation
 * when price consolidates after ORB, then pushes to fresh intraday highs in mid-session.
 * These are different stocks from ORB signals on the same day, giving additive signal count.
 *
 * Entry:
 *   1. Tue/Wed/Thu only (Mon/Fri excluded — same rationale as ORB)
 *   2. Window 11:30–13:00 IST (post-ORB-resolution, pre-late-day noise)
 *   3. Stock is above orbHigh (ORB already resolved bullish on this stock)
 *   4. Current candle's close is the HIGHEST close of the session so far
 *   5. Breakout above previous intraday high by 0.15–0.8% (conviction, not overextended)
 *   6. Bullish candle: body ≥ 55%, close > open
 *   7. Upper wick ≤ 25% (no rejection at highs)
 *   8. Volume ≥ 2.0x 20-bar avg
 *   9. Close above VWAP (bullish intraday structure)
 *   10. RSI 50–68 (momentum building, not yet overbought)
 *   11. Gap ≥ -0.3% (allow flat/slight gap-down but skip big negative gaps)
 *
 * SL: 0.15% below the previous intraday high (key support now)
 * Target: 1.5:1 (continuation moves are smaller than initial ORB breaks)
 */
@Slf4j
@Component
public class IntradayHighBreakoutStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "INTRADAY_HIGH_BREAKOUT"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 30) return null;

        // Window: 11:30–13:00 IST (post-ORB-resolution, cut afternoon where momentum weakens)
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 11 * 60 + 30) return null;
            if (min > 13 * 60)       return null;
        }

        // Tue/Wed/Thu only
        java.time.LocalDateTime ts = candles.get(n - 1).timestamp();
        if (ts != null) {
            java.time.DayOfWeek dow = ts.getDayOfWeek();
            if (dow == java.time.DayOfWeek.MONDAY || dow == java.time.DayOfWeek.FRIDAY) return null;
        }

        BigDecimal orbHigh = context.extra("orbHigh", BigDecimal.class);
        BigDecimal orbLow  = context.extra("orbLow",  BigDecimal.class);
        if (orbHigh == null || orbLow == null) return null;

        Candle curr  = candles.get(n - 1);
        BigDecimal close = curr.close();
        double closeD = close.doubleValue();

        // Must be above orbHigh (ORB already resolved bullish)
        if (close.compareTo(orbHigh) < 0) return null;

        // Find highest close of TODAY (scan backward to day boundary — window spans multiple days)
        java.time.LocalDate today = candles.get(n - 1).timestamp().toLocalDate();
        double intradayHighClose = 0;
        for (int k = n - 2; k >= 0; k--) {
            if (!candles.get(k).timestamp().toLocalDate().equals(today)) break;
            intradayHighClose = Math.max(intradayHighClose, candles.get(k).close().doubleValue());
        }
        if (intradayHighClose <= 0) return null;

        // Current close must be a NEW session high
        if (closeD <= intradayHighClose) return null;

        // Breakout: 0.15%–0.8% above prior session high
        double breakoutPct = (closeD - intradayHighClose) / intradayHighClose;
        if (breakoutPct < 0.0015) return null;
        if (breakoutPct > 0.008) return null;

        // Bullish candle
        if (close.compareTo(curr.open()) <= 0) return null;
        BigDecimal range = curr.high().subtract(curr.low());
        if (range.compareTo(BigDecimal.ZERO) <= 0) return null;

        // Body ≥ 55%
        double bodyPct = close.subtract(curr.open()).divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        if (bodyPct < 0.55) return null;

        // Upper wick ≤ 25%
        double upperWick = curr.high().doubleValue() - closeD;
        if (upperWick / range.doubleValue() > 0.25) return null;

        // Volume ≥ 2.0x 20-bar avg
        int volLen = Math.min(20, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        if (avgVol == 0) return null;
        double volMult = (double) curr.volume() / avgVol;
        if (volMult < 2.0) return null;

        // Close above VWAP
        BigDecimal vwapBD = context.extra("vwap", BigDecimal.class);
        if (vwapBD != null && close.compareTo(vwapBD) < 0) return null;

        // RSI 50–68
        BigDecimal rsi14bd = context.extra("rsi14", BigDecimal.class);
        if (rsi14bd != null) {
            double rsi = rsi14bd.doubleValue();
            if (rsi < 50 || rsi > 68) return null;
        }

        // Gap ≥ -0.3% (allow flat/slight gap-down but skip big negative gaps)
        BigDecimal prevDayClose = context.extra("prevDayClose", BigDecimal.class);
        BigDecimal dayOpen      = context.extra("dayOpen",      BigDecimal.class);
        if (prevDayClose != null && dayOpen != null && prevDayClose.compareTo(BigDecimal.ZERO) > 0) {
            double gapPct = (dayOpen.doubleValue() - prevDayClose.doubleValue()) / prevDayClose.doubleValue();
            if (gapPct < -0.003) return null;
        }

        // SL: 0.15% below previous session high
        BigDecimal prevHighBD = BigDecimal.valueOf(intradayHighClose);
        BigDecimal sl = prevHighBD.multiply(BigDecimal.valueOf(0.9985)).setScale(2, RoundingMode.HALF_UP);
        double risk = closeD - sl.doubleValue();
        if (risk <= 0) return null;
        double riskPct = risk / closeD;
        if (riskPct > 0.012) return null;
        if (riskPct < 0.002) return null;

        // 1.5:1 target
        BigDecimal target = close.add(BigDecimal.valueOf(1.5 * risk)).setScale(2, RoundingMode.HALF_UP);

        int score = 50;
        if (volMult >= 2.5) score += 20;
        else if (volMult >= 1.8) score += 10;
        if (bodyPct >= 0.70) score += 15;
        if (breakoutPct >= 0.003) score += 15;

        log.debug("IHB: {} prevH={} break={}% vol={}x body={}% risk={}% score={}",
            context.symbol(),
            String.format("%.2f", intradayHighClose),
            String.format("%.2f", breakoutPct * 100),
            String.format("%.1f", volMult),
            String.format("%.0f", bodyPct * 100),
            String.format("%.2f", riskPct * 100),
            score);

        return new Signal(
            context.symbol(), Signal.Side.BUY, close, sl, target,
            score / 100.0,
            "IHB @" + close.setScale(2, RoundingMode.HALF_UP)
                + " prevH=" + prevHighBD.setScale(2, RoundingMode.HALF_UP)
                + " brk=" + String.format("%.2f%%", breakoutPct * 100)
                + " vol=" + String.format("%.1fx", volMult)
                + " sl=" + sl + " risk=" + String.format("%.2f%%", riskPct * 100),
            1.2, 0.6);
    }
}
