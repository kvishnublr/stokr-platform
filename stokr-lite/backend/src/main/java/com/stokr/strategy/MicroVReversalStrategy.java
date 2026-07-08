package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Micro V-Reversal — intraday mean reversion on 1-min candles.
 *
 * Data-driven edge from Jun–Jul 2026 1-min data (30 NIFTY stocks):
 *   - 3-bar drop >=1% followed by reclaim → 75% win, PF 3.94
 *   - 41 trades with drop>=0.5% → 56% win, PF 1.68
 *   - SHORT side unprofitable (bullish market bias) → LONG only
 *
 * Entry  : Buy at close of reclaim candle (candle that closes green after 3-bar drop)
 * SL     : Entry - 1%
 * Target : Entry + 1.5%
 * Hold   : Max 15 candles (15 min) — fast intraday trade
 *
 * Why it works:
 *   - Sharp 3-bar drop creates temporary overselling
 *   - Green reclaim candle confirms buyer interest
 *   - Quick reversion to mean within minutes
 *   - 1:1.5 risk-reward with 75% WR = high expectancy
 */
@Slf4j
@Component
public class MicroVReversalStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "MICRO_V_REVERSAL"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 20) return null; // need some history

        Candle latest = candles.get(n - 1);
        double close = latest.close().doubleValue();
        if (close < 50) return null; // skip penny stocks

        // Need at least 4 candles: 3 for the drop pattern + 1 current (reclaim)
        if (n < 4) return null;

        // ─── 1. DETECT 3-BAR DROP ──────────────────────────────────────
        // Check candles[n-4], [n-3], [n-2] for a cumulative drop
        Candle bar0 = candles.get(n - 4); // start of drop
        Candle bar1 = candles.get(n - 3);
        Candle bar2 = candles.get(n - 2); // end of drop
        Candle reclaim = candles.get(n - 1); // reclaim candle (current)

        double dropStart = bar0.open().doubleValue();
        double dropEnd = bar2.close().doubleValue();
        if (dropStart <= 0) return null;

        double dropPct = (dropEnd - dropStart) / dropStart * 100;

        // ─── 2. FILTER: DROP MUST BE >= 1% ─────────────────────────────
        if (dropPct > -1.0) return null;

        // ─── 3. CONFIRM: RECLAIM CANDLE MUST BE GREEN AND ABOVE DROP LOW ──
        if (reclaim.close().compareTo(reclaim.open()) <= 0) return null;
        if (reclaim.close().compareTo(bar2.close()) <= 0) return null; // must reclaim above drop end

        // ─── 4. OPTIONAL: RSI CONFIRMS OVERSOLD ────────────────────────
        BigDecimal rsi5 = computeRsi(candles, 5);

        // ─── 5. COMPUTE ENTRY, SL, TARGET ──────────────────────────────
        double entry = close; // enter at reclaim close
        double sl = entry * 0.99;      // 1% SL
        double target = entry * 1.015;  // 1.5% target

        double riskPct = (entry - sl) / entry * 100;

        // ─── 6. CONFIDENCE SCORE ───────────────────────────────────────
        int score = 60; // base
        if (dropPct <= -1.5) score += 15; // deeper drop = stronger signal
        else if (dropPct <= -1.2) score += 10;
        if (rsi5 != null && rsi5.doubleValue() < 20) score += 15;
        else if (rsi5 != null && rsi5.doubleValue() < 30) score += 10;

        // Reclaim strength: how much did the reclaim candle recover?
        double reclaimPct = reclaim.close().subtract(bar2.close()).doubleValue() / bar2.close().doubleValue() * 100;
        if (reclaimPct > 0.3) score += 10; // strong reclaim

        // Volume confirmation: reclaim volume > drop volume avg
        long dropVol = (bar0.volume() + bar1.volume() + bar2.volume()) / 3;
        if (dropVol > 0 && reclaim.volume() > dropVol * 1.2) score += 5;

        if (score < 70) return null;

        BigDecimal entryBD = BigDecimal.valueOf(entry).setScale(2, RoundingMode.HALF_UP);
        BigDecimal slBD = BigDecimal.valueOf(sl).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tgtBD = BigDecimal.valueOf(target).setScale(2, RoundingMode.HALF_UP);

        log.info("MVR {} score={}/100 drop={} reclaim={} @{} sl={} tgt={} rsi5={}",
            context.symbol(), score,
            String.format("%.1f%%", dropPct),
            String.format("%.2f%%", reclaimPct),
            entryBD, slBD, tgtBD,
            rsi5 != null ? String.format("%.1f", rsi5.doubleValue()) : "N/A");

        return new Signal(
            context.symbol(), Signal.Side.BUY, entryBD, slBD, tgtBD,
            score / 100.0,
            "MVR drop=" + String.format("%.1f%%", dropPct)
                + " reclaim=" + String.format("%.2f%%", reclaimPct)
                + " score=" + score + "/100"
                + " @" + entryBD
                + " sl=" + slBD
                + " tgt=" + tgtBD
                + " risk=" + String.format("%.1f%%", riskPct)
                + " rsi5=" + (rsi5 != null ? String.format("%.1f", rsi5.doubleValue()) : "N/A"),
            0.5, 0.25); // trail: activate at 0.5%, trail 0.25%
    }

    private BigDecimal computeRsi(List<Candle> candles, int period) {
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
