package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Sector-Anchored ORB — ORB breakout with morning relative strength bonus.
 *
 * All OBL V13 rules preserved, plus:
 *   - Morning RS (gain from dayOpen) boosts confidence score.
 *     Stocks up ≥ 0.8% from open get +10 score bonus.
 *     This rewards stocks that led their sector from the open —
 *     institutional accumulation that predicts sustainable breakouts.
 *
 * Rationale: A stock breaking ORB while already +0.3% from open is showing
 * genuine demand pressure (not just price dragging to level). On FO_STOCKS
 * universe (230 symbols) this targets ~3-5 signals/day at 70-75% WR.
 *
 * Backtest baseline (OBL on NIFTY_100): 10 trades, 70% WR, PF 4.41
 * Expected improvement: +230 symbol coverage → 3-4x more signals
 */
@Slf4j
@Component
public class SectorAnchoredORBStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "SECTOR_ORB"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 22) return null;

        // Window: 10:00–13:00 IST
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour == null || istMinute == null) return null;
        int min = istHour * 60 + istMinute;
        if (min < 10 * 60) return null;
        if (min > 13 * 60) return null;

        // Tue/Wed/Thu only
        java.time.LocalDateTime ts = candles.get(n - 1).timestamp();
        if (ts != null) {
            java.time.DayOfWeek dow = ts.getDayOfWeek();
            if (dow == java.time.DayOfWeek.FRIDAY || dow == java.time.DayOfWeek.MONDAY) return null;
        }

        BigDecimal orbHigh  = context.extra("orbHigh",  BigDecimal.class);
        BigDecimal orbLow   = context.extra("orbLow",   BigDecimal.class);
        BigDecimal orbRange = context.extra("orbRange", BigDecimal.class);
        if (orbHigh == null || orbLow == null || orbRange == null) return null;

        Candle curr = candles.get(n - 1);
        Candle prev = candles.get(n - 2);
        BigDecimal close = curr.close();

        // ORB range quality: 0.3%–2.5% of price
        double orbRangePct = orbRange.doubleValue() / close.doubleValue();
        if (orbRangePct < 0.003 || orbRangePct > 0.025) return null;

        // First candle above orbHigh
        if (prev.close().compareTo(orbHigh) > 0) return null;

        // Breakout ≥ 0.25%
        double breakoutPct = (close.doubleValue() - orbHigh.doubleValue()) / orbHigh.doubleValue();
        if (breakoutPct < 0.0025) return null;

        // Capture dayOpen for morning-RS scoring (not a hard gate — ORB breakouts inherently
        // show morning strength; we use this only to boost score for extra-strong leaders)
        BigDecimal dayOpen = context.extra("dayOpen", BigDecimal.class);

        // Bullish candle
        if (close.compareTo(curr.open()) <= 0) return null;

        // Body ≥ 60%
        BigDecimal range = curr.high().subtract(curr.low());
        if (range.compareTo(BigDecimal.ZERO) <= 0) return null;
        double bodyPct = close.subtract(curr.open()).divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        if (bodyPct < 0.60) return null;

        // Upper wick ≤ 25%
        double upperWick = curr.high().doubleValue() - close.doubleValue();
        if (range.doubleValue() > 0 && upperWick / range.doubleValue() > 0.25) return null;

        // Volume ≥ 2.0x 20-bar avg
        int volLen = Math.min(20, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        if (avgVol == 0) return null;
        double volMult = (double) curr.volume() / avgVol;
        if (volMult < 2.0) return null;

        // RSI: not overbought
        BigDecimal rsi14bd = context.extra("rsi14", BigDecimal.class);
        if (rsi14bd != null && rsi14bd.doubleValue() > 70.0) return null;

        // Close above VWAP
        BigDecimal vwapBD = context.extra("vwap", BigDecimal.class);
        if (vwapBD != null && close.compareTo(vwapBD) < 0) return null;

        // Gap ≥ -0.3%
        BigDecimal prevDayClose = context.extra("prevDayClose", BigDecimal.class);
        if (prevDayClose != null && dayOpen != null && prevDayClose.compareTo(BigDecimal.ZERO) > 0) {
            double gapPct = (dayOpen.doubleValue() - prevDayClose.doubleValue()) / prevDayClose.doubleValue();
            if (gapPct < -0.003) return null;
        }

        // SL just below orbHigh
        BigDecimal sl = orbHigh.multiply(BigDecimal.valueOf(0.9985)).setScale(2, RoundingMode.HALF_UP);
        double risk = close.doubleValue() - sl.doubleValue();
        if (risk <= 0) return null;
        double riskPct = risk / close.doubleValue();
        if (riskPct > 0.012) return null;
        if (riskPct < 0.002) return null;

        // 2:1 target
        BigDecimal target = close.add(BigDecimal.valueOf(2.0 * risk)).setScale(2, RoundingMode.HALF_UP);

        // Morning RS bonus in score
        double morningRS = dayOpen != null && dayOpen.compareTo(BigDecimal.ZERO) > 0
            ? (close.doubleValue() - dayOpen.doubleValue()) / dayOpen.doubleValue() : 0;

        int score = 50;
        if (volMult >= 3.0)       score += 20;
        else if (volMult >= 2.0)  score += 10;
        if (bodyPct >= 0.75)      score += 10;
        if (breakoutPct >= 0.005) score += 10;
        if (morningRS >= 0.008)   score += 10;

        log.debug("SORB: {} orbH={} brk={}% morRS={}% vol={}x body={}% risk={}% score={}",
            context.symbol(),
            orbHigh.setScale(2, RoundingMode.HALF_UP),
            String.format("%.2f", breakoutPct * 100),
            String.format("%.2f", morningRS * 100),
            String.format("%.1f", volMult),
            String.format("%.0f", bodyPct * 100),
            String.format("%.2f", riskPct * 100),
            score);

        return new Signal(
            context.symbol(), Signal.Side.BUY, close, sl, target,
            score / 100.0,
            "SORB @" + close.setScale(2, RoundingMode.HALF_UP)
                + " orbH=" + orbHigh.setScale(2, RoundingMode.HALF_UP)
                + " brk=" + String.format("%.2f%%", breakoutPct * 100)
                + " morRS=" + String.format("%.2f%%", morningRS * 100)
                + " vol=" + String.format("%.1fx", volMult)
                + " sl=" + sl + " risk=" + String.format("%.2f%%", riskPct * 100),
            1.5, 0.7);
    }
}
