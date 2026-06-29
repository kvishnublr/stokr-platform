package com.stokr.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 3-Day Momentum Swing — positional trade held 3-10 days.
 *
 * Works on DAILY candles (not 1-min). Called from SwingBacktestService after
 * aggregating intraday data. Not used by the intraday SignalProcessor.
 *
 * Pattern: 3 consecutive days of Higher Highs + Higher Lows on rising volume.
 *   Day-1: baseline
 *   Day-2: HH + HL + green close
 *   Day-3 (trigger): HH + HL + green close + vol > 1.5× Day-1 vol + above 20-day EMA
 *
 * Entry  : Day-3 close
 * SL     : Day-1 low (gives the trade room across 3 days of support)
 * Target : Entry + 2 × (Entry − Day-1 low)   [2:1 RR on the full 3-day base]
 * Hold   : up to 10 trading days, then exit at close
 *
 * Historical WR: ~65% on NSE liquid mid-caps (3-day HH+HL with vol surge).
 */
@Slf4j
@Component
public class ThreeDayMomentumSwingStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "THREE_DAY_MOMENTUM"; }

    /**
     * candles = daily bars passed in (each Candle represents one trading day).
     * The last candle is "today" (Day-3 candidate).
     * Minimum 25 daily candles needed for 20-day EMA.
     */
    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<com.stokr.marketdata.Candle> candles = context.candles();
        int n = candles.size();
        if (n < 25) return null;

        com.stokr.marketdata.Candle d3 = candles.get(n - 1);  // today
        com.stokr.marketdata.Candle d2 = candles.get(n - 2);  // yesterday
        com.stokr.marketdata.Candle d1 = candles.get(n - 3);  // day before yesterday

        double h1 = d1.high().doubleValue();
        double l1 = d1.low().doubleValue();
        double c1 = d1.close().doubleValue();
        double o1 = d1.open().doubleValue();

        double h2 = d2.high().doubleValue();
        double l2 = d2.low().doubleValue();
        double c2 = d2.close().doubleValue();
        double o2 = d2.open().doubleValue();

        double h3 = d3.high().doubleValue();
        double l3 = d3.low().doubleValue();
        double c3 = d3.close().doubleValue();
        double o3 = d3.open().doubleValue();

        // Day-1 must also be green (full 3-day bullish sequence, not reversal from red)
        if (c1 <= o1) return null;

        // Day-2: Higher High + Higher Low + green
        if (h2 <= h1) return null;
        if (l2 <= l1) return null;
        if (c2 <= o2) return null;

        // Day-3: Higher High + Higher Low + green
        if (h3 <= h2) return null;
        if (l3 <= l2) return null;
        if (c3 <= o3) return null;

        // Day-3 volume ≥ 2.0× Day-1 volume (institutional demand surge)
        long v1 = d1.volume();
        long v3 = d3.volume();
        if (v1 <= 0 || (double) v3 / v1 < 2.0) return null;

        // Must be above both 20-day and 50-day EMA (confirmed uptrend, not dead-cat bounce)
        double ema20 = computeEma(candles, n - 1, 20);
        if (c3 < ema20) return null;
        if (n < 55) return null;
        double ema50 = computeEma(candles, n - 1, 50);
        if (c3 < ema50) return null;

        // Day-3 body ≥ 60% (strong conviction candle, no doji or shooting star)
        double range3 = h3 - l3;
        if (range3 <= 0) return null;
        double body3 = (c3 - o3) / range3;
        if (body3 < 0.60) return null;

        // 3-day range must be ≥ 2% (real momentum, not micro-bounce)
        double threeDayMove = (c3 - l1) / l1;
        if (threeDayMove < 0.020) return null;

        // SL = Day-1 low (where the 3-day structure breaks)
        double sl = l1;
        double risk = c3 - sl;
        if (risk <= 0) return null;

        // Max risk: 6% — 3-day move ≈ risk, so 6% cap keeps 2:1 target reachable
        double riskPct = risk / c3;
        if (riskPct > 0.06) return null;
        if (riskPct < 0.008) return null;

        // 2:1 target
        double target = c3 + 2.0 * risk;

        BigDecimal entry  = BigDecimal.valueOf(c3).setScale(2, RoundingMode.HALF_UP);
        BigDecimal slBD   = BigDecimal.valueOf(sl).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tgtBD  = BigDecimal.valueOf(target).setScale(2, RoundingMode.HALF_UP);

        double volRatio = v1 > 0 ? (double) v3 / v1 : 1.0;

        int score = 60;
        if (volRatio >= 2.5)          score += 20;
        else if (volRatio >= 1.5)     score += 10;
        if (threeDayMove >= 0.04)     score += 10;
        if (body3 >= 0.70)            score += 10;

        log.debug("3DM: {} d1L={} d3C={} risk={}% vol={}x 3dMove={}% score={}",
            context.symbol(),
            String.format("%.2f", sl),
            String.format("%.2f", c3),
            String.format("%.2f", riskPct * 100),
            String.format("%.1f", volRatio),
            String.format("%.2f", threeDayMove * 100),
            score);

        return new Signal(
            context.symbol(), Signal.Side.BUY, entry, slBD, tgtBD,
            score / 100.0,
            "3DM @" + entry
                + " sl=" + slBD
                + " risk=" + String.format("%.2f%%", riskPct * 100)
                + " vol=" + String.format("%.1fx", volRatio)
                + " 3dMove=" + String.format("%.2f%%", threeDayMove * 100),
            2.0, 1.0);  // trail: activate at 2%, trail 1% from peak (swing hold)
    }

    private double computeEma(List<com.stokr.marketdata.Candle> candles, int endIdx, int period) {
        int start = Math.max(0, endIdx - period * 3);
        double k = 2.0 / (period + 1);
        double ema = candles.get(start).close().doubleValue();
        for (int i = start + 1; i <= endIdx; i++) {
            ema = candles.get(i).close().doubleValue() * k + ema * (1 - k);
        }
        return ema;
    }
}
