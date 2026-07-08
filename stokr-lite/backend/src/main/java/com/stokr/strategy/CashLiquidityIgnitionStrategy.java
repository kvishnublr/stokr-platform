package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Cash Liquidity Ignition — Dynamic intraday sudden-move strategy.
 *
 * 3-stage funnel: Candidate → Confirmation → Execution
 * Master scoring (0-10) with regime veto, dynamic trail, time-stop.
 *
 * Score components:
 *   +2  relative volume > 2.5x
 *   +2  1-min range > 1.7x ATR
 *   +2  break of ORB high / prev day high / VWAP reclaim
 *   +1  Nifty aligned (flat-to-positive)
 *   +1  candle closes in top 25% of range
 *   -2  large rejection wick (>45% against direction)
 *   -2  already moved >1.5% in last 3 candles
 *   -2  spread proxy widened (range/close > 0.8%)
 *
 * Entry: BUY above ignition candle high (confirmed breakout)
 * SL: below ignition candle low, max 0.8% distance
 * Trail: after +0.3% gain, trail at 0.3% below peak
 * Partial: 50% at 1R, trail the rest
 * Time-stop: configurable candles (default 5)
 * Daily circuit breaker: max losses/day → stop
 */
@Slf4j
@Component
public class CashLiquidityIgnitionStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "CASH_IGNITION";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 25) return null; // need enough history for indicators + scoring

        // ─── TIME WINDOW FILTER ───────────────────────────────────────────
        Integer istHour   = context.extra("istHour",   Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour == null || istMinute == null) return null;
        int min = istHour * 60 + istMinute;

        // Two windows: 09:25-10:45 and 13:45-14:45
        boolean window1 = min >= 9 * 60 + 25 && min <= 10 * 60 + 45;
        boolean window2 = min >= 13 * 60 + 45 && min <= 14 * 60 + 45;
        if (!window1 && !window2) return null;

        Candle latest = context.getLatestCandle();
        Candle prev   = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        BigDecimal close = latest.close();
        BigDecimal high  = latest.high();
        BigDecimal low   = latest.low();
        BigDecimal open  = latest.open();
        BigDecimal range = high.subtract(low);

        if (range.compareTo(BigDecimal.ZERO) <= 0) return null;
        if (close.compareTo(BigDecimal.ZERO) <= 0) return null;

        // ─── REGIME DETECTION (ADX-based) ─────────────────────────────────
        BigDecimal adx = context.extra("adx14", BigDecimal.class);
        // ADX needs ~27 bars to warm up. If null or < 20, treat as range-bound
        // but don't veto — just skip the regime bonus
        boolean isTrending = adx != null && adx.doubleValue() > 25;
        boolean isQuiet    = adx != null && adx.doubleValue() < 15;
        if (isQuiet) return null; // regime veto: no trade in dead markets

        // ─── VWAP ─────────────────────────────────────────────────────────
        BigDecimal vwap = context.vwap();
        if (vwap == null || vwap.compareTo(BigDecimal.ZERO) <= 0) return null;

        // ─── ATR ──────────────────────────────────────────────────────────
        BigDecimal atr = context.extra("atr14", BigDecimal.class);
        if (atr == null || atr.compareTo(BigDecimal.ZERO) <= 0) return null;

        // ─── ORB LEVELS ───────────────────────────────────────────────────
        BigDecimal orbHigh  = context.extra("orbHigh",  BigDecimal.class);
        BigDecimal orbLow   = context.extra("orbLow",   BigDecimal.class);
        if (orbHigh == null || orbLow == null) return null;

        // ─── PREVIOUS DAY LEVELS ──────────────────────────────────────────
        BigDecimal prevDayHigh = context.extra("prevDayHigh", BigDecimal.class);
        BigDecimal prevDayLow  = context.extra("prevDayLow",  BigDecimal.class);

        // ─── NIFTY ALIGNMENT ──────────────────────────────────────────────
        Double niftyPct = context.extra("niftyPctChange", Double.class);
        boolean niftyAligned = niftyPct == null || niftyPct >= -0.15;

        // ═══════════════════════════════════════════════════════════════════
        // STAGE 1: CANDIDATE — compute master score
        // ═══════════════════════════════════════════════════════════════════
        int score = 0;

        // 1. Relative volume: current candle volume vs 20-period average
        BigDecimal volSma = context.extra("VOL_SMA_10", BigDecimal.class);
        // Also compute a 20-period average directly from candles if VOL_SMA_10
        // is only 10-period. For accuracy, compute from last 20 candles.
        double avgVol20 = computeAvgVol(candles, 20);
        double currentVol = latest.volume();
        double volRatio = avgVol20 > 0 ? currentVol / avgVol20 : 0;
        if (volRatio >= 3.0) score += 2;
        else if (volRatio >= 2.5) score += 2;
        else if (volRatio >= 2.0) score += 1;
        // else 0

        // 2. Range expansion: current range vs ATR
        double rangePct = range.doubleValue() / close.doubleValue();
        double atrPct = atr.doubleValue() / close.doubleValue();
        double rangeExpansion = atrPct > 0 ? rangePct / atrPct : 0;
        if (rangeExpansion >= 2.0) score += 2;
        else if (rangeExpansion >= 1.7) score += 2;
        else if (rangeExpansion >= 1.3) score += 1;

        // 3. Breakout: price breaks ORB high, prev day high, or reclaims VWAP
        boolean brokeOrbHigh = close.compareTo(orbHigh) > 0;
        boolean brokePrevDayHigh = prevDayHigh != null && close.compareTo(prevDayHigh) > 0;
        boolean vwapReclaim = close.compareTo(vwap) > 0
                && prev.close().compareTo(vwap) <= 0; // was below, now above
        boolean brokeLevel = brokeOrbHigh || brokePrevDayHigh || vwapReclaim;
        if (brokeLevel) score += 2;

        // 4. Nifty aligned
        if (niftyAligned) score += 1;

        // 5. Candle closes in top 25% of range
        double closePosition = (close.doubleValue() - low.doubleValue()) / range.doubleValue();
        if (closePosition >= 0.75) score += 1;

        // ─── NEGATIVE SCORES ──────────────────────────────────────────────

        // 6. Large rejection wick against direction (>45% of range)
        double upperWick = (high.doubleValue() - Math.max(open.doubleValue(), close.doubleValue())) / range.doubleValue();
        double lowerWick = (Math.min(open.doubleValue(), close.doubleValue()) - low.doubleValue()) / range.doubleValue();
        // For BUY: large upper wick = rejection at highs = bad
        if (upperWick > 0.45) score -= 2;
        // Large lower wick on a green candle is fine (buyers defended)

        // 7. Already moved >1.5% in last 3 candles
        if (n >= 4) {
            double move3 = (close.doubleValue() - candles.get(n - 4).close().doubleValue())
                    / candles.get(n - 4).close().doubleValue() * 100;
            if (Math.abs(move3) > 1.5) score -= 2;
        }

        // 8. Spread proxy: range/close > 0.8% (wide spread = illiquid)
        double spreadProxy = rangePct * 100;
        if (spreadProxy > 0.8) score -= 2;

        // ═══════════════════════════════════════════════════════════════════
        // STAGE 2: CONFIRMATION — score gate + structural checks
        // ═══════════════════════════════════════════════════════════════════
        // Stricter: require score >= 8 (was params threshold = 7)
        if (score < 8) return null;

        // Must be a bullish candle (close > open) with strong close
        if (close.compareTo(open) <= 0) return null;

        // Price must be above VWAP
        if (close.compareTo(vwap) <= 0) return null;

        // Must have broken a level (not just random volume)
        if (!brokeLevel) return null;

        // RSI must confirm — not overbought yet (room to run)
        BigDecimal rsi = context.extra("rsi14", BigDecimal.class);
        if (rsi != null && rsi.doubleValue() > 72) return null;

        // Must close in top 30% of candle range (strong buying pressure)
        double closePos = (close.doubleValue() - low.doubleValue()) / range.doubleValue();
        if (closePos < 0.70) return null;

        // ═══════════════════════════════════════════════════════════════════
        // STAGE 3: EXECUTION — compute entry, SL, target
        // ═══════════════════════════════════════════════════════════════════

        // Entry: above candle high (confirmed breakout)
        BigDecimal entry = high;

        // SL: below ignition candle low
        BigDecimal sl = low;

        // Check SL distance — reject if too wide
        double slDist = (entry.doubleValue() - sl.doubleValue()) / entry.doubleValue() * 100;
        if (slDist > params.ignitionMaxSlPct()) {
            log.debug("CI filtered: SL distance {:.2f}% > max {:.2f}% for {}",
                    slDist, params.ignitionMaxSlPct(), context.symbol());
            return null;
        }
        // Also reject if SL distance is too tight (< 0.1%) — likely a Doji
        if (slDist < 0.1) return null;

        // Target: 2R (wider target for higher R:R)
        double riskPerShare = entry.doubleValue() - sl.doubleValue();
        BigDecimal target = entry.add(BigDecimal.valueOf(riskPerShare * 2.0));

        // Trending regime bonus: widen trail slightly
        double trailTrigger = params.ignitionTrailTriggerPct();
        double trailDist = params.ignitionTrailDistancePct();
        if (isTrending) {
            trailDist = Math.min(trailDist * 1.3, 0.5);
        }
        // Wider trail for 2R target
        trailTrigger = Math.max(trailTrigger, 0.3);
        trailDist = Math.max(trailDist, 0.2);

        // Build reason string
        String breakoutType = brokeOrbHigh ? "ORB_BREAK" :
                              brokePrevDayHigh ? "PDH_BREAK" : "VWAP_RECLAIM";
        double riskReward = riskPerShare > 0 ? (target.doubleValue() - entry.doubleValue()) / riskPerShare : 2.0;

        String reason = "CASH_IGNITION " + breakoutType
                + " score=" + score + "/" + params.ignitionScoreThreshold()
                + " @" + entry.setScale(2, RoundingMode.HALF_UP)
                + " sl=" + sl.setScale(2, RoundingMode.HALF_UP)
                + " vol=" + String.format("%.1fx", volRatio)
                + " range=" + String.format("%.1fxATR", rangeExpansion)
                + " adx=" + (adx != null ? adx : "N/A");

        log.info("CI signal: {} {}", context.symbol(), reason);

        return new Signal(
                context.symbol(),
                Signal.Side.BUY,
                entry,
                sl,
                target,
                Math.min(score / 10.0, 1.0),
                reason,
                trailTrigger,
                trailDist
        );
    }

    /**
     * Compute average volume over last `period` candles (excluding the latest candle).
     * Uses candles.get(n-1-period) to candles.get(n-2) — avoids counting the signal candle itself.
     */
    private double computeAvgVol(List<Candle> candles, int period) {
        int n = candles.size();
        int start = Math.max(0, n - 1 - period);
        int end = n - 1; // exclude latest candle
        if (end <= start) return 0;
        long sum = 0;
        for (int i = start; i < end; i++) {
            sum += candles.get(i).volume();
        }
        return (double) sum / (end - start);
    }
}
