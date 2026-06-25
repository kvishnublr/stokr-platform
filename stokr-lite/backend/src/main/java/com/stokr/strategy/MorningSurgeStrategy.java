package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Morning Surge Strategy — high-conviction ORB variation for explosive gap days.
 *
 * Fixes applied:
 *   1. SL capped at entry × 0.995 (0.5% max risk) — prevents full-ORB-range SLs of 1.5–2%
 *   2. ORB range cap: skip if orbRange/orbHigh > 1% (wide opening = too volatile)
 *   3. Trail trigger 1.0% (was 0.5%), trail distance 0.5% (was 0.3%) — lets winners run
 *   4. Volume avg uses prior candles only (bug fix — excludes current candle)
 */
@Slf4j
@Component
public class MorningSurgeStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "MORNING_SURGE"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 20) return null;

        BigDecimal orbHigh  = context.extra("orbHigh",  BigDecimal.class);
        BigDecimal orbLow   = context.extra("orbLow",   BigDecimal.class);
        BigDecimal orbRange = context.extra("orbRange", BigDecimal.class);
        if (orbHigh == null || orbLow == null || orbRange == null) return null;
        if (orbRange.compareTo(BigDecimal.valueOf(0.01)) < 0) return null;

        // FIX 2: Skip wide ORB days — range > 1% of orbHigh means too volatile, SL too far
        double orbRangePct = orbRange.doubleValue() / orbHigh.doubleValue() * 100;
        if (orbRangePct > 1.0) return null;

        // Tight morning window: 9:30–10:30 IST
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 9 * 60 + 30 || min > 10 * 60 + 30) return null;
        }

        Candle latest = context.getLatestCandle();
        Candle prev   = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        BigDecimal close = latest.close();

        // 1. Breakout above ORB high
        if (close.compareTo(orbHigh) <= 0) return null;

        // 2. First breakout only
        if (prev.close().compareTo(orbHigh) > 0) return null;

        // 3. No chase — entry within 1% of ORB high
        if (close.compareTo(orbHigh.multiply(BigDecimal.valueOf(1.01))) > 0) return null;

        // 4. Trend day: close > day open
        BigDecimal dayOpen = context.extra("dayOpen", BigDecimal.class);
        if (dayOpen != null && close.compareTo(dayOpen) <= 0) return null;

        // 5. Strong bullish candle: body ≥ 60% of range
        if (close.compareTo(latest.open()) <= 0) return null;
        BigDecimal range = latest.high().subtract(latest.low());
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal bodyPct = close.subtract(latest.open())
                .divide(range, 4, RoundingMode.HALF_UP);
            if (bodyPct.doubleValue() < 0.60) return null;
        }

        // 6. HIGH volume ≥ 3× 10-period average — FIX 4: exclude current candle from avg
        int volLen = Math.min(10, n - 1);
        long volSum = 0;
        for (int k = n - 1 - volLen; k < n - 1; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;
        if (avgVol == 0 || latest.volume() < avgVol * 3) return null;

        // FIX 1: Cap SL at 0.5% below entry — prevents huge losses on wide-range days
        BigDecimal slFromOrb   = orbLow.multiply(BigDecimal.valueOf(0.999)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal slMaxAllowed = close.multiply(BigDecimal.valueOf(0.995)).setScale(2, RoundingMode.HALF_UP);
        // Use whichever is higher (tighter stop)
        BigDecimal sl = slFromOrb.compareTo(slMaxAllowed) > 0 ? slFromOrb : slMaxAllowed;
        if (sl.compareTo(close) >= 0) return null;

        // Target: 2× ORB extension
        BigDecimal target = orbHigh.add(orbRange.multiply(BigDecimal.valueOf(2)))
            .setScale(2, RoundingMode.HALF_UP);

        if (target.compareTo(close) <= 0) return null;

        double rrRatio = target.subtract(close).doubleValue() / close.subtract(sl).doubleValue();
        if (rrRatio < 1.0) return null;

        // FIX 3: Trail trigger 1.0%, trail distance 0.5% — lets winners run to target
        return new Signal(
            context.symbol(), Signal.Side.BUY, close, sl, target,
            0.72,
            "SURGE @" + close.setScale(2, RoundingMode.HALF_UP)
                + " orb=[" + orbLow.setScale(2, RoundingMode.HALF_UP)
                + "-" + orbHigh.setScale(2, RoundingMode.HALF_UP) + "]"
                + " orbRng=" + String.format("%.2f%%", orbRangePct)
                + " tgt=" + target
                + " rr=" + String.format("%.1f", rrRatio)
                + " vol=" + String.format("%.1fx", (double) latest.volume() / avgVol),
            1.0,   // trail trigger: 1.0% gain (was 0.5%)
            0.5    // trail distance: 0.5% below peak (was 0.3%)
        );
    }
}
