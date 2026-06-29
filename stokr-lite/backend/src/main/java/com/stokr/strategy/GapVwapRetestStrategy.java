package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Gap + VWAP Retest Long — Triple Confirmation Pullback Entry.
 *
 * Three conditions must ALIGN to take a trade:
 *   1. GAP UP: stock opened >= 0.4% above previous close (institutional momentum)
 *   2. ORB BREAKOUT: price is above orbHigh (day structure is bullish)
 *   3. VWAP RETEST: price pulled back to VWAP (prev low touched VWAP ± 0.3%)
 *      and is now bouncing (close > VWAP + 0.15%)
 *
 * Logic: Gapped-up stocks above their ORB are in strong uptrends.
 * When they dip to VWAP, it's a natural pullback — buying the bounce
 * has very high probability because all three forces (gap momentum,
 * ORB breakout, VWAP support) are aligned in the same direction.
 *
 * SL: 0.25% below VWAP (if VWAP breaks, trade is wrong)
 * Target: 1.5x risk (fixed — quick scalp, don't overstay)
 * Trail: 1.0% trigger, 0.5% trail (for the occasional big runner)
 * Window: 10:00–13:30 IST
 *
 * Expected WR: 60–70%
 */
@Slf4j
@Component
public class GapVwapRetestStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "GAP_VWAP_RETEST"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 22) return null;

        // Window: 10:00–13:30 IST
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 10 * 60)       return null;
            if (min > 13 * 60 + 30) return null;
        }

        // --- CONFIRMATION 1: GAP UP ---
        BigDecimal prevDayClose = context.extra("prevDayClose", BigDecimal.class);
        BigDecimal dayOpen      = context.extra("dayOpen",      BigDecimal.class);
        if (prevDayClose == null || dayOpen == null || prevDayClose.compareTo(BigDecimal.ZERO) <= 0) return null;
        double gapPct = (dayOpen.doubleValue() - prevDayClose.doubleValue()) / prevDayClose.doubleValue();
        if (gapPct < 0.004) return null;  // minimum 0.4% gap up

        // --- CONFIRMATION 2: ABOVE ORB HIGH (day structure bullish) ---
        BigDecimal orbHigh  = context.extra("orbHigh",  BigDecimal.class);
        BigDecimal orbLow   = context.extra("orbLow",   BigDecimal.class);
        BigDecimal orbRange = context.extra("orbRange", BigDecimal.class);
        if (orbHigh == null || orbLow == null) return null;

        Candle curr = candles.get(n - 1);
        BigDecimal close = curr.close();
        Candle prev = candles.get(n - 2);

        if (close.compareTo(orbHigh) < 0) return null;  // must be above orbHigh

        // --- CONFIRMATION 3: VWAP RETEST + BOUNCE ---
        BigDecimal vwapBD = context.extra("vwap", BigDecimal.class);
        if (vwapBD == null) return null;
        double vwap = vwapBD.doubleValue();
        if (vwap <= 0) return null;

        // Current close must be above VWAP by at least 0.15% (bounce confirmed)
        double closeD = close.doubleValue();
        double aboveVwapPct = (closeD - vwap) / vwap;
        if (aboveVwapPct < 0.0015) return null;

        // Previous candle's low must have touched VWAP zone (genuine retest)
        double prevLow = prev.low().doubleValue();
        if (prevLow > vwap * 1.003) return null;   // didn't actually touch VWAP
        if (prevLow < vwap * 0.994) return null;   // went too far below VWAP (breakdown, not retest)

        // Previous candle must have closed at or below VWAP (dipped in, not just touched on spike)
        if (prev.close().doubleValue() > vwap * 1.002) return null;

        // --- CANDLE QUALITY ---
        // Current candle must be bullish
        if (close.compareTo(curr.open()) <= 0) return null;

        // Body >= 50% of range
        BigDecimal range = curr.high().subtract(curr.low());
        if (range.compareTo(BigDecimal.ZERO) <= 0) return null;
        double bodyPct = close.subtract(curr.open()).divide(range, 4, RoundingMode.HALF_UP).doubleValue();
        if (bodyPct < 0.50) return null;

        // Volume >= 1.5x avg (bounce must have conviction)
        int volLen = Math.min(20, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        double volMult = avgVol > 0 ? (double) curr.volume() / avgVol : 0;
        if (volMult < 1.5) return null;

        // RSI: 35–68 (not oversold crash, not overbought)
        BigDecimal rsi14bd = context.extra("rsi14", BigDecimal.class);
        if (rsi14bd != null) {
            double rsi = rsi14bd.doubleValue();
            if (rsi < 35 || rsi > 68) return null;
        }

        // --- SIZING ---
        // SL: 0.25% below VWAP (VWAP break = trade wrong)
        BigDecimal vwapBig = BigDecimal.valueOf(vwap);
        BigDecimal sl = vwapBig.multiply(BigDecimal.valueOf(0.9975)).setScale(2, RoundingMode.HALF_UP);
        double risk = closeD - sl.doubleValue();
        if (risk <= 0) return null;

        double riskPct = risk / closeD;
        if (riskPct > 0.015) return null;  // too far from VWAP — don't chase
        if (riskPct < 0.002) return null;  // too tight — whipsaw risk

        // Fixed 1.5:1 target (scalp-style — don't overstay VWAP bounces)
        BigDecimal target = close.add(BigDecimal.valueOf(1.5 * risk)).setScale(2, RoundingMode.HALF_UP);

        log.debug("GVR: {} gap={}% above_vwap={}% vol={}x body={}% risk={}%",
            context.symbol(),
            String.format("%.2f", gapPct * 100),
            String.format("%.2f", aboveVwapPct * 100),
            String.format("%.1f", volMult),
            String.format("%.0f", bodyPct * 100),
            String.format("%.2f", riskPct * 100));

        return new Signal(
            context.symbol(), Signal.Side.BUY, close, sl, target,
            0.72,
            "GVR @" + close.setScale(2, RoundingMode.HALF_UP)
                + " gap=" + String.format("%.2f%%", gapPct * 100)
                + " vwap=" + vwapBig.setScale(2, RoundingMode.HALF_UP)
                + " orbH=" + orbHigh.setScale(2, RoundingMode.HALF_UP)
                + " vol=" + String.format("%.1fx", volMult)
                + " sl=" + sl + " tgt=" + target,
            1.0, 0.5);  // trail: activate at 1%, trail 0.5% from peak
    }
}
