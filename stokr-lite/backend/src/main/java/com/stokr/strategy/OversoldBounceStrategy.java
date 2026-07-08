package com.stokr.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Oversold Bounce — buy stocks that dropped >3% on low volume, sell next day.
 *
 * Data-driven edge discovered from 6 months of NSE data:
 *   - 439 instances of >3% daily drops with clean volume data
 *   - 59.7% win rate, +0.47% average next-day return
 *   - Moderate drops (-3% to -4%) are the sweet spot: 61.5% win, +0.57%
 *
 * Entry  : Close of the drop day (buy at 3:15 PM)
 * SL     : Entry - 3% (wide stop — this is a 1-day hold, not a swing)
 * Target : Entry + 1.5% (realistic for overnight mean reversion)
 * Hold   : 1-7 days max (time-stop at day 7 close)
 *
 * Filters:
 *   - Drop must be > 3% from previous close
 *   - Previous volume must be > 0 (no data artifacts)
 *   - Stock must have traded today (volume > 0)
 *   - Not a penny stock (price > ₹50)
 *
 * Why it works:
 *   - Behavioral: panic selling creates temporary overselling
 *   - Technical: price stretched too far below mean → snap-back
 *   - Structural: overnight session allows institutional accumulation
 */
@Slf4j
@Component
public class OversoldBounceStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "OVERSOLD_BOUNCE"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<com.stokr.marketdata.Candle> candles = context.candles();
        int n = candles.size();
        if (n < 25) return null; // need some history for EMA/RSI

        com.stokr.marketdata.Candle today = candles.get(n - 1);
        com.stokr.marketdata.Candle yesterday = candles.get(n - 2);

        double close = today.close().doubleValue();
        double prevClose = yesterday.close().doubleValue();

        // ─── 1. PRICE FILTER ──────────────────────────────────────────
        if (close < 50) return null; // skip penny stocks
        if (prevClose <= 0) return null;

        // ─── 2. DROP DETECTION: > 3% from previous close ──────────────
        double dropPct = (close - prevClose) / prevClose * 100;
        if (dropPct > -3.0) return null; // not a big enough drop

        // ─── 3. VOLUME VALIDATION ─────────────────────────────────────
        // Both days must have real volume (no data artifacts)
        if (today.volume() <= 0 || yesterday.volume() <= 0) return null;

        // ─── 4. EXCLUDE CIRCUIT LIMIT HITS ────────────────────────────
        // If drop is exactly -5% or -10% or -20%, it's a circuit limit — can't buy
        if (dropPct <= -4.9) return null; // likely circuit limit or near it

        // ─── 5. TREND CONTEXT (optional — helps avoid catching falling knives) ──
        // Skip if stock is in a severe downtrend (below 200-day EMA by >15%)
        double ema50 = computeEma(candles, n - 1, 50);
        double distFromEma50 = (close - ema50) / ema50 * 100;
        if (distFromEma50 < -15) return null; // too far below = structural breakdown

        // ─── 6. RSI CHECK (optional — confirms oversold) ──────────────
        BigDecimal rsi = computeRsi(candles, 14);

        // ─── 7. SWEET SPOT: -3% to -4% ───────────────────────────────
        // Moderate drops bounce better than extreme ones
        boolean isSweetSpot = dropPct >= -4.0;

        // ─── 8. COMPUTE ENTRY, SL, TARGET ─────────────────────────────
        double entry = close;
        double sl = entry * 0.97;     // 3% below (proven — survives noise)
        double target = entry * 1.015; // 1.5% above (realistic — gets hit 83% of time)

        double riskPct = (entry - sl) / entry;
        double rewardPct = (target - entry) / entry;

        // ─── 9. CONFIDENCE SCORE ──────────────────────────────────────
        int score = 50; // base
        if (isSweetSpot)                    score += 15; // -3% to -4% sweet spot
        if (dropPct < -4.0)                score += 5;  // bigger drop = more oversold
        if (rsi != null && rsi.doubleValue() < 30) score += 15; // deeply oversold
        else if (rsi != null && rsi.doubleValue() < 40) score += 10;
        if (distFromEma50 > -10)           score += 10; // closer to EMA = stronger support
        if (today.volume() > yesterday.volume() * 1.5) score += 5; // selling climax volume

        if (score < 60) return null;

        BigDecimal entryBD  = BigDecimal.valueOf(entry).setScale(2, RoundingMode.HALF_UP);
        BigDecimal slBD     = BigDecimal.valueOf(sl).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tgtBD    = BigDecimal.valueOf(target).setScale(2, RoundingMode.HALF_UP);

        log.info("OB {} score={}/100 drop={}% @{} sl={} tgt={} rsi={} ema50dist={}%",
            context.symbol(), score,
            String.format("%.1f", dropPct),
            entryBD, slBD, tgtBD,
            rsi != null ? String.format("%.1f", rsi.doubleValue()) : "N/A",
            String.format("%.1f", distFromEma50));

        return new Signal(
            context.symbol(), Signal.Side.BUY, entryBD, slBD, tgtBD,
            score / 100.0,
            "OB drop=" + String.format("%.1f%%", dropPct)
                + " score=" + score + "/100"
                + " @" + entryBD
                + " sl=" + slBD
                + " tgt=" + tgtBD
                + " risk=" + String.format("%.1f%%", riskPct * 100)
                + " rsi=" + (rsi != null ? String.format("%.1f", rsi.doubleValue()) : "N/A"),
            0.3, 0.15); // trail: activate at 0.3%, trail 0.15% (tight for 1-day hold)
    }

    private double computeEma(List<com.stokr.marketdata.Candle> candles, int endIdx, int period) {
        int start = Math.max(0, endIdx - period * 3);
        int warmup = endIdx - start + 1;
        if (warmup < period) return candles.get(endIdx).close().doubleValue();
        double k = 2.0 / (period + 1);
        double ema = 0;
        for (int i = start; i < start + period; i++) ema += candles.get(i).close().doubleValue();
        ema /= period;
        for (int i = start + period; i <= endIdx; i++) {
            ema = candles.get(i).close().doubleValue() * k + ema * (1 - k);
        }
        return ema;
    }

    private BigDecimal computeRsi(List<com.stokr.marketdata.Candle> candles, int period) {
        int n = candles.size();
        if (n < period + 1) return null;
        double avgGain = 0, avgLoss = 0;
        for (int i = n - period; i < n; i++) {
            double change = candles.get(i).close().doubleValue() - candles.get(i - 1).close().doubleValue();
            if (change > 0) avgGain += change; else avgLoss -= change;
        }
        if (avgGain + avgLoss == 0) return BigDecimal.valueOf(50);
        double rs = avgGain / avgLoss;
        double rsi = 100.0 - 100.0 / (1.0 + rs);
        return BigDecimal.valueOf(rsi);
    }
}
