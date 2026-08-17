package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Tactical Swing Breakout (VCP) Strategy - V2
 *
 * Institutional-grade VCP scanner.
 * 1. Minervini Trend Template (Stage 2 Uptrend).
 * 2. Sequential Volatility Contraction & Volume Dry-up.
 * 3. ATR-based Adaptive Stop Loss.
 * 4. No price filter (scans all cash equities).
 */
@Slf4j
@Component
public class VcpBreakoutStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "VCP_BREAKOUT";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 210) return null; // Need 200 days for Trend Template

        Candle today = candles.get(n - 1);
        double close = today.close().doubleValue();
        if (today.volume() <= 0) return null;

        // 1. Minervini Trend Template
        double ema50 = computeEma(candles, n - 1, 50);
        double ema150 = computeEma(candles, n - 1, 150);
        double ema200 = computeEma(candles, n - 1, 200);

        if (close < ema150 || close < ema200) return null;
        if (ema150 < ema200) return null;
        if (close < ema50) return null;

        // 52-Week High Check (approx 250 trading days)
        double high52w = 0;
        for (int i = Math.max(0, n - 250); i < n; i++) {
            double h = candles.get(i).high().doubleValue();
            if (h > high52w) high52w = h;
        }
        if (close < high52w * 0.75) return null; // Must be within 25% of 52-week high

        // 2. Sequential Contraction Phase (last 20 days)
        double maxHigh20 = 0;
        double minLow20 = Double.MAX_VALUE;
        long sumVol20 = 0;

        for (int i = n - 21; i < n - 1; i++) {
            double h = candles.get(i).high().doubleValue();
            double l = candles.get(i).low().doubleValue();
            if (h > maxHigh20) maxHigh20 = h;
            if (l < minLow20) minLow20 = l;
            sumVol20 += candles.get(i).volume();
        }

        double avgVol20 = sumVol20 / 20.0;
        
        // Measure tightness of the base
        double baseTightness = (maxHigh20 - minLow20) / minLow20 * 100;
        if (baseTightness > 15.0) return null; // Not tight enough

        // Volume Dry-up Check (last 5 days volume should be lower than 20-day avg)
        long sumVol5 = 0;
        for (int i = n - 6; i < n - 1; i++) {
            sumVol5 += candles.get(i).volume();
        }
        double avgVol5 = sumVol5 / 5.0;
        if (avgVol5 > avgVol20) return null; // Volume is not drying up

        // 3. Breakout Condition
        if (close <= maxHigh20) return null;

        // 4. Volume Confirmation
        if (today.volume() < avgVol20 * 1.5) return null;

        // 5. Adaptive ATR-based Stop Loss
        double atr14 = computeAtr(candles, 14);
        double slDistance = atr14 * 1.5; // 1.5x ATR
        
        double entry = close;
        double sl = entry - slDistance;
        
        // Ensure SL is logical (not below the contraction low)
        if (sl < minLow20 * 0.96) {
            sl = minLow20 * 0.96; // Hard floor just below the base
        }

        double risk = entry - sl;
        if (risk <= 0) return null;
        
        double target = entry + (risk * 4.0); // 1:4 Risk/Reward

        int score = 85;
        if (today.volume() > avgVol20 * 2.5) score += 10;
        if (baseTightness < 10.0) score += 5;

        BigDecimal entryBD = BigDecimal.valueOf(entry).setScale(2, RoundingMode.HALF_UP);
        BigDecimal slBD = BigDecimal.valueOf(sl).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tgtBD = BigDecimal.valueOf(target).setScale(2, RoundingMode.HALF_UP);

        log.info(String.format("VCP_V2 %s score=%d/100 tightness=%.1f%% @%s sl=%s tgt=%s",
            context.symbol(), score, baseTightness, entryBD, slBD, tgtBD));

        return new Signal(
            context.symbol(), Signal.Side.BUY, entryBD, slBD, tgtBD,
            score / 100.0,
            "VCP V2 tightness=" + String.format("%.1f%%", baseTightness) + " score=" + score + "/100",
            2.0, 1.0); // Trail activates at 2% gain, trails by 1%
    }

    private double computeEma(List<Candle> candles, int endIdx, int period) {
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
    
    private double computeAtr(List<Candle> candles, int period) {
        int n = candles.size();
        if (n < period + 1) return 0.0;
        double atr = 0.0;
        for (int i = n - period; i < n; i++) {
            Candle curr = candles.get(i);
            Candle prev = candles.get(i - 1);
            double high = curr.high().doubleValue();
            double low = curr.low().doubleValue();
            double prevClose = prev.close().doubleValue();
            
            double tr1 = high - low;
            double tr2 = Math.abs(high - prevClose);
            double tr3 = Math.abs(low - prevClose);
            double tr = Math.max(tr1, Math.max(tr2, tr3));
            
            atr += tr;
        }
        return atr / period;
    }
}
