package com.stokr.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * RSI Oversold v3 — buy when RSI(14) drops below 35, with soft confirmations.
 *
 * v1: 85.4% WR, PF 4.44, +₹12,180/3mo — 41 trades, no hard filters
 * v2: 85.7% WR, +₹1,715/3mo — 7 trades, hard filters too strict (killed 83%)
 * v3: Relaxed filters — volume > 1.2x (was 1.5x), support within 5% (was 3%)
 *
 * Entry  : Close of the oversold day (buy at 3:15 PM)
 * SL     : Entry - 3%
 * Target : EMA50 (mean reversion)
 * Hold   : 1-5 days max
 * Trail  : 0.5% trigger, 0.25% trail
 */
@Slf4j
@Component
public class RsiOversoldStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "RSI_OVERSOLD"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<com.stokr.marketdata.Candle> candles = context.candles();
        int n = candles.size();
        if (n < 50) return null;

        com.stokr.marketdata.Candle today = candles.get(n - 1);
        double close = today.close().doubleValue();
        if (close < 50) return null;
        if (today.volume() <= 0) return null;

        // ─── 1. COMPUTE RSI(14) ────────────────────────────────────
        BigDecimal rsi14 = computeRsi(candles, 14);
        if (rsi14 == null) return null;
        double rsiVal = rsi14.doubleValue();
        if (rsiVal >= 35) return null;

        // ─── 2. BEARISH CANDLE ─────────────────────────────────────
        // Down day confirms selling — not a reversal candle
        if (today.close().doubleValue() >= today.open().doubleValue()) return null;

        // ─── 3. VOLUME FILTER (relaxed) ────────────────────────────
        // Volume > 1.2x 10-period average — confirms above-average selling
        // v2 used 1.5x which killed 83% of trades
        long avgVol = computeAvgVolume(candles, n, 10);
        if (avgVol == 0 || today.volume() < avgVol * 1.2) return null;

        // ─── 4. SUPPORT LEVEL CHECK (relaxed) ──────────────────────
        // Price within 5% of 20-day low — confirms floor exists
        // v2 used 3% which was too restrictive
        double low20 = Double.MAX_VALUE;
        for (int i = Math.max(0, n - 20); i < n; i++) {
            double low = candles.get(i).low().doubleValue();
            if (low < low20) low20 = low;
        }
        double distFromLow = (close - low20) / low20 * 100;
        if (distFromLow > 5.0) return null;

        // ─── 5. COMPUTE EMA20 & EMA50 for context ──────────────────
        double ema20 = computeEma(candles, n - 1, 20);
        double ema50 = computeEma(candles, n - 1, 50);

        // ─── 6. DISTANCE FROM EMA50 (deeper = more oversold) ───────
        double distEma50 = (close - ema50) / ema50 * 100;

        // ─── 7. RSI EXTREMITY (deeper = better) ────────────────────
        boolean isExtreme = rsiVal < 25;

        // ─── 8. COMPUTE ENTRY, SL, TARGET ──────────────────────────
        double entry = close;
        double sl = entry * 0.97;       // 3% SL
        double target = ema50;           // target = EMA50 (mean reversion)

        // ─── 9. RISK:REWARD CHECK ──────────────────────────────────
        double riskPct = (entry - sl) / entry;
        double rewardPct = (target - entry) / entry;
        if (rewardPct <= 0 || rewardPct / riskPct < 1.0) return null;

        // ─── 10. CONFIDENCE SCORE ──────────────────────────────────
        int score = 40;
        if (rsiVal < 20)                score += 20;
        else if (rsiVal < 25)           score += 15;
        else if (rsiVal < 30)           score += 10;
        else                            score += 5;
        if (distEma50 < -5)             score += 10;
        if (distEma50 < -8)             score += 5;
        if (isExtreme)                  score += 5;
        if (close > ema20)              score += 5;
        if (today.volume() > avgVol * 2.0) score += 5;
        if (distFromLow < 2.0)          score += 5;

        if (score < 55) return null;

        BigDecimal entryBD = BigDecimal.valueOf(entry).setScale(2, RoundingMode.HALF_UP);
        BigDecimal slBD = BigDecimal.valueOf(sl).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tgtBD = BigDecimal.valueOf(target).setScale(2, RoundingMode.HALF_UP);

        log.info(String.format("RSI_OVR v3 %s score=%d/100 rsi=%.1f @%s sl=%s tgt=%s dist50=%.1f%% sup=%.1f%%",
            context.symbol(), score, rsiVal, entryBD, slBD, tgtBD, distEma50, distFromLow));

        return new Signal(
            context.symbol(), Signal.Side.BUY, entryBD, slBD, tgtBD,
            score / 100.0,
            "RSI_OVR v3 rsi=" + String.format("%.1f", rsiVal)
                + " score=" + score + "/100"
                + " @" + entryBD
                + " sl=" + slBD
                + " tgt=" + tgtBD
                + " risk=" + String.format("%.1f%%", riskPct * 100)
                + " dist50=" + String.format("%.1f%%", distEma50)
                + " sup=" + String.format("%.1f%%", distFromLow),
            0.5, 0.25);
    }

    private double computeEma(List<com.stokr.marketdata.Candle> candles, int endIdx, int period) {
        int start = Math.max(0, endIdx - period * 3);
        double k = 2.0 / (period + 1);
        double ema = 0;
        for (int i = start; i < start + period && i <= endIdx; i++) ema += candles.get(i).close().doubleValue();
        ema /= Math.min(period, endIdx - start + 1);
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
        for (int i = Math.max(0, n - period); i < n; i++) vol += candles.get(i).volume();
        return vol / Math.min(period, n);
    }
}
