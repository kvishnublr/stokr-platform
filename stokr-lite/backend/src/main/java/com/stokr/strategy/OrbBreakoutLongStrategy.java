package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * ORB Breakout Long — high-conviction opening-range breakouts on Tue/Wed/Thu only.
 *
 * Entry (LONG only):
 *   1. Tue/Wed/Thu only — Mondays (chaotic ORB after weekend) and Fridays (risk-off reversals) excluded
 *   2. Window 10:00–13:00 IST — post-opening-volatility settlement
 *   3. ORB range 0.3%–2.5% of price (meaningful, not too wide/choppy)
 *   4. First candle to CLOSE above orbHigh by ≥ 0.25% (first breakout only)
 *   5. Bullish candle: close > open, body ≥ 60% of range, upper wick ≤ 25%
 *   6. Volume ≥ 2.0x 20-bar avg (genuine surge, not drift)
 *   7. Close above VWAP (bullish intraday structure)
 *   8. Gap ≥ -0.3% (skip big gap-down days)
 *   9. SL 0.15% below orbHigh, max risk 1.2%, 2:1 target
 *
 * Backtest (Mar–May 2026, NIFTY_100): 10 trades, 70% WR, PF 4.41, Max DD ₹71
 */
@Slf4j
@Component
public class OrbBreakoutLongStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "ORB_BREAKOUT_LONG"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 22) return null;

        // Window: 10:00–13:00 — post-opening-volatility, quality breakout window
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 10 * 60)       return null;
            if (min > 13 * 60)       return null;
        }

        // Skip Fridays and Mondays — weekend bracket days have chaotic ORB ranges
        // Fridays: late-day risk-off selling reverses breakouts; Mondays: post-weekend gaps make ORB unreliable
        if (!candles.isEmpty()) {
            java.time.LocalDateTime ts = candles.get(candles.size() - 1).timestamp();
            if (ts != null) {
                java.time.DayOfWeek dow = ts.getDayOfWeek();
                if (dow == java.time.DayOfWeek.FRIDAY || dow == java.time.DayOfWeek.MONDAY) return null;
            }
        }

        BigDecimal orbHigh  = context.extra("orbHigh",  BigDecimal.class);
        BigDecimal orbLow   = context.extra("orbLow",   BigDecimal.class);
        BigDecimal orbRange = context.extra("orbRange", BigDecimal.class);
        if (orbHigh == null || orbLow == null || orbRange == null) return null;

        Candle curr = candles.get(n - 1);
        Candle prev = candles.get(n - 2);
        BigDecimal close = curr.close();

        // ORB range quality: 0.3%–2.5% of price (not too tight, not too wild)
        double orbRangePct = orbRange.doubleValue() / close.doubleValue();
        if (orbRangePct < 0.003 || orbRangePct > 0.025) return null;

        // Must be the FIRST candle above orbHigh (prev was at or below)
        if (prev.close().compareTo(orbHigh) > 0) return null;

        // Close must be above orbHigh by at least 0.25% (conviction breakout)
        double breakoutPct = (close.doubleValue() - orbHigh.doubleValue()) / orbHigh.doubleValue();
        if (breakoutPct < 0.0025) return null;

        // Bullish candle
        if (close.compareTo(curr.open()) <= 0) return null;

        // Body >= 60% of candle range
        BigDecimal range = curr.high().subtract(curr.low());
        if (range.compareTo(BigDecimal.ZERO) <= 0) return null;
        double bodyPct = close.subtract(curr.open()).divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        if (bodyPct < 0.60) return null;

        // Volume >= 2.0x 20-bar avg
        int volLen = Math.min(20, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;
        if (avgVol == 0) return null;
        double volMult = (double) curr.volume() / avgVol;
        if (volMult < 2.0) return null;

        // RSI: not overbought
        BigDecimal rsi14bd = context.extra("rsi14", BigDecimal.class);
        if (rsi14bd != null && rsi14bd.doubleValue() > 70.0) return null;

        // VWAP alignment: close must be above VWAP (bullish intraday structure)
        BigDecimal vwapBD = context.extra("vwap", BigDecimal.class);
        if (vwapBD != null && close.compareTo(vwapBD) < 0) return null;

        // Candle quality: upper wick must not exceed 25% of range (no rejection at highs)
        double upperWick = curr.high().doubleValue() - close.doubleValue();
        if (range.doubleValue() > 0 && upperWick / range.doubleValue() > 0.25) return null;

        // Gap up bias: stock must have opened above previous close (momentum day)
        BigDecimal prevDayClose = context.extra("prevDayClose", BigDecimal.class);
        BigDecimal dayOpen      = context.extra("dayOpen",      BigDecimal.class);
        if (prevDayClose != null && dayOpen != null && prevDayClose.compareTo(BigDecimal.ZERO) > 0) {
            double gapPct = (dayOpen.doubleValue() - prevDayClose.doubleValue()) / prevDayClose.doubleValue();
            if (gapPct < -0.003) return null;  // allow slight gap-down (≥ -0.3%), skip big gap-downs
        }

        // SL just below orbHigh
        BigDecimal sl = orbHigh.multiply(BigDecimal.valueOf(0.9985)).setScale(2, RoundingMode.HALF_UP);

        double risk = close.doubleValue() - sl.doubleValue();
        if (risk <= 0) return null;

        double riskPct = risk / close.doubleValue();
        if (riskPct > 0.012) return null;
        if (riskPct < 0.002) return null;

        // Fixed 2:1 target
        BigDecimal target = close.add(BigDecimal.valueOf(2.0 * risk)).setScale(2, RoundingMode.HALF_UP);

        // Confidence score
        int score = 50;  // base: passed all gates
        if (volMult >= 3.0)     score += 20;
        else if (volMult >= 2.0) score += 10;
        if (bodyPct >= 0.75)    score += 15;
        if (breakoutPct >= 0.005) score += 15;

        log.debug("OBL: {} orb={} breakout={}% vol={}x body={}% risk={}% score={}",
            context.symbol(),
            orbHigh.setScale(2, RoundingMode.HALF_UP),
            String.format("%.2f", breakoutPct * 100),
            String.format("%.1f", volMult),
            String.format("%.0f", bodyPct * 100),
            String.format("%.2f", riskPct * 100),
            score);

        return new Signal(
            context.symbol(), Signal.Side.BUY, close, sl, target,
            score / 100.0,
            "OBL @" + close.setScale(2, RoundingMode.HALF_UP)
                + " orbH=" + orbHigh.setScale(2, RoundingMode.HALF_UP)
                + " brk=" + String.format("%.2f%%", breakoutPct * 100)
                + " vol=" + String.format("%.1fx", volMult)
                + " sl=" + sl + " risk=" + String.format("%.2f%%", riskPct * 100),
            1.5, 0.7);   // trail: activate at 1.5%, trail 0.7% from peak
    }
}
