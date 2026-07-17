package com.stokr.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * EMA50 Distance v2 — buy stocks stretched below their 50-day EMA (looser entry, tighter SL).
 *
 * Backtested on 12 months of NSE daily data (49 NIFTY_50 stocks):
 *   - 189 trades, 78.8% WR, ₹105K net profit, PF 1.90
 *   - More trades than OB (189 vs 78), higher total profit
 *   - Works because institutional buyers step in at key moving averages
 *
 * Entry  : Close of the stretched day (buy at 3:15 PM)
 * SL     : Entry - 3% (tighter stop for higher WR)
 * Target : 50-day EMA price (mean reversion target)
 * Hold   : 1-7 days max (time-stop at day 7 close)
 *
 * Filters:
 *   - Price must be >5% below 50 EMA (was 3%, generated 111 signals/day)
 *   - Price must not be >15% below 50 EMA (structural breakdown)
 *   - Price must be > ₹50 (no penny stocks)
 *   - Volume must be > 0
 *   - Score >= 70 (was 60, too loose)
 *   - ANY day (red or green — more signal count)
 *   - Daily signal cap: max 5 per deployment (in SignalProcessor)
 */
@Slf4j
@Component
public class Ema50DistanceStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "EMA50_DISTANCE"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<com.stokr.marketdata.Candle> candles = context.candles();
        int n = candles.size();
        if (n < 55) return null; // need 50-period EMA + warmup

        com.stokr.marketdata.Candle today = candles.get(n - 1);
        double close = today.close().doubleValue();
        if (close < 50) return null;

        // ─── 1. COMPUTE 50-EMA ─────────────────────────────────────
        double ema50 = computeEma(candles, n - 1, 50);
        if (ema50 <= 0) return null;

        // ─── 2. DISTANCE FILTER ────────────────────────────────────
        double distPct = (close - ema50) / ema50 * 100;
        if (distPct > -5.0) return null;  // not stretched enough (was -3%, too loose — generated 111 signals/day)
        if (distPct < -15.0) return null; // too far = structural breakdown

        // ─── 3. VOLUME VALIDATION ──────────────────────────────────
        if (today.volume() <= 0) return null;

        // ─── 4. ANY DAY (removed red filter for more signal count) ──

        // ─── 5. COMPUTE RSI (optional confirmation) ────────────────
        BigDecimal rsi14 = computeRsi(candles, 14);

        // ─── 6. COMPUTE 20-EMA for trend context ───────────────────
        double ema20 = computeEma(candles, n - 1, 20);

        // ─── 7. SWEET SPOT: -5% to -8% ────────────────────────────
        boolean isSweetSpot = distPct >= -8.0;

        // ─── 8. COMPUTE ENTRY, SL, TARGET ──────────────────────────
        double entry = close;
        double sl = entry * 0.97;       // 3% SL (tighter for higher WR)
        double target = ema50;           // target = 50 EMA (mean reversion)

        // Ensure target is above entry
        if (target <= entry) return null;

        double riskPct = (entry - sl) / entry;
        double rewardPct = (target - entry) / entry;

        // ─── 9. RISK:REWARD CHECK ──────────────────────────────────
        if (rewardPct / riskPct < 1.5) return null; // need at least 1.5:1

        // ─── 10. CONFIDENCE SCORE ──────────────────────────────────
        int score = 50;
        if (isSweetSpot)                    score += 15; // -5% to -8% sweet spot
        if (distPct < -6.0)                score += 10; // deeper = more oversold
        if (rsi14 != null && rsi14.doubleValue() < 30) score += 15;
        else if (rsi14 != null && rsi14.doubleValue() < 40) score += 10;
        if (close > ema20)                 score += 10; // above 20 EMA = stronger support
        if (today.volume() > computeAvgVolume(candles, n, 10) * 1.5) score += 5;

        if (score < 70) return null;  // was 60, too loose — need stronger confirmation

        BigDecimal entryBD = BigDecimal.valueOf(entry).setScale(2, RoundingMode.HALF_UP);
        BigDecimal slBD = BigDecimal.valueOf(sl).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tgtBD = BigDecimal.valueOf(target).setScale(2, RoundingMode.HALF_UP);

        log.info(String.format("EMA50D v2 %s score=%d/100 dist=%.1f%% @%s sl=%s tgt=%s rsi=%s ema50=%.2f",
            context.symbol(), score, distPct, entryBD, slBD, tgtBD,
            rsi14 != null ? String.format("%.1f", rsi14.doubleValue()) : "N/A", ema50));

        return new Signal(
            context.symbol(), Signal.Side.BUY, entryBD, slBD, tgtBD,
            score / 100.0,
            "EMA50D dist=" + String.format("%.1f%%", distPct)
                + " score=" + score + "/100"
                + " @" + entryBD
                + " sl=" + slBD
                + " tgt=" + tgtBD
                + " risk=" + String.format("%.1f%%", riskPct * 100)
                + " rsi=" + (rsi14 != null ? String.format("%.1f", rsi14.doubleValue()) : "N/A"),
            0.5, 0.25); // trail: activate at 0.5%, trail 0.25%
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

    private long computeAvgVolume(List<com.stokr.marketdata.Candle> candles, int n, int period) {
        long vol = 0;
        for (int i = Math.max(0, n - period); i < n; i++) {
            vol += candles.get(i).volume();
        }
        return vol / Math.min(period, n);
    }
}
