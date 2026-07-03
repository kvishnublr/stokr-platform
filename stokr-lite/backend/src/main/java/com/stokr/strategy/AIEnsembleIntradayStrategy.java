package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * AI Ensemble Intraday Strategy — 12-factor adaptive scoring system.
 *
 * Combines multiple technical factors with regime-adaptive weights to
 * generate high-conviction intraday signals. Unlike single-factor strategies,
 * this ensemble adapts to market conditions (trending/ranging/volatile).
 *
 * 12 Factors:
 *   F1  Volume Momentum   — current vol vs 10-SMA
 *   F2  Price Momentum     — 5-candle ROC
 *   F3  RSI Proximity      — distance from 50 (momentum axis)
 *   F4  VWAP Deviation     — price vs VWAP
 *   F5  ORB Breakout       — above/below opening range
 *   F6  Candle Body Ratio  — bullish/bearish conviction
 *   F7  ATR Volatility     — normalized ATR
 *   F8  EMA Trend          — EMA8 vs EMA21 alignment
 *   F9  Gap from Prev      — gap from previous close
 *   F10 Volume-Price Trend — accelerating/decelerating
 *   F11 Consecutive Direction — streak strength
 *   F12 Intraday Pattern   — morning momentum vs afternoon drift
 *
 * Regime Detection:
 *   TRENDING  — ATR high + EMA aligned → favor momentum factors
 *   RANGING   — ATR low + EMA crossing → favor mean-reversion
 *   HIGH_VOL  — ATR > 1.5% → wider thresholds, smaller position
 *   LOW_VOL   — ATR < 0.3% → skip (not enough movement)
 */
@Slf4j
@Component
public class AIEnsembleIntradayStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "AI_ENSEMBLE";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 21) return null;

        Candle latest = context.getLatestCandle();
        Candle prev = context.getPreviousCandle();
        if (latest == null || prev == null) return null;

        BigDecimal close = latest.close();
        BigDecimal open = latest.open();
        if (close == null || close.compareTo(BigDecimal.ZERO) <= 0) return null;

        // Time gate: 9:25–14:30 IST
        Integer istHour = context.extra("istHour", Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int totalMin = istHour * 60 + istMinute;
            if (totalMin < 9 * 60 + 20 || totalMin > 14 * 60 + 45) return null;
        }

        // ---- Compute 12 factors ----
        double[] scores = new double[12];

        // F1: Volume Momentum (current vol vs 10-SMA)
        long volSum = 0;
        int volLen = Math.min(10, n);
        for (int k = n - volLen; k < n; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;
        double volRatio = avgVol > 0 ? (double) latest.volume() / avgVol : 1.0;
        scores[0] = normalize(volRatio, 0.5, 4.0); // 0-1, 2.0 = neutral

        // F2: Price Momentum (5-candle rate of change)
        int rocLen = Math.min(5, n);
        BigDecimal prevPrice = candles.get(n - rocLen).close();
        if (prevPrice.compareTo(BigDecimal.ZERO) > 0) {
            double roc = close.subtract(prevPrice).doubleValue() / prevPrice.doubleValue() * 100;
            scores[1] = normalize(roc, -1.0, 1.0); // ±1.5% mapped to 0-1
        }

        // F3: RSI proximity to 50
        BigDecimal rsi = context.indicators() != null ? context.indicators().get("RSI14") : null;
        if (rsi == null) rsi = context.extra("rsi14", BigDecimal.class);
        if (rsi != null) {
            double rsiVal = rsi.doubleValue();
            if (rsiVal > 50) {
                scores[2] = 0.5 + (rsiVal - 50) / 100.0; // 50-100 → 0.5-1.0
            } else {
                scores[2] = (rsiVal) / 100.0; // 0-50 → 0.0-0.5
            }
        }

        // F4: VWAP Deviation
        BigDecimal vwap = context.vwap();
        if (vwap != null && vwap.compareTo(BigDecimal.ZERO) > 0) {
            double vwapDev = close.subtract(vwap).doubleValue() / vwap.doubleValue() * 100;
            scores[3] = normalize(vwapDev, -1.0, 1.0); // ±1% mapped to 0-1
        }

        // F5: ORB Breakout
        BigDecimal orbHigh = context.extra("orbHigh", BigDecimal.class);
        BigDecimal orbLow = context.extra("orbLow", BigDecimal.class);
        if (orbHigh != null && orbLow != null) {
            double orbRange = orbHigh.subtract(orbLow).doubleValue();
            if (orbRange > 0) {
                double orbPos = close.subtract(orbLow).doubleValue() / orbRange;
                scores[4] = Math.max(0, Math.min(1, orbPos));
            }
        }

        // F6: Candle Body Ratio (bullish conviction)
        BigDecimal range = latest.high().subtract(latest.low());
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            double bodyPct = close.subtract(open).abs().doubleValue() / range.doubleValue();
            scores[5] = bodyPct; // 0-1 already
            // Directional bias
            if (close.compareTo(open) > 0) {
                scores[5] = 0.5 + bodyPct * 0.5; // bullish candle: 0.5-1.0
            } else {
                scores[5] = 0.5 - bodyPct * 0.5; // bearish candle: 0.0-0.5
            }
        }

        // F7: ATR Volatility (normalized)
        double atrPct = computeATRPct(candles, 14, close);
        scores[6] = normalize(atrPct, 0.1, 2.0); // 0.1%-2.0% → 0-1

        // F8: EMA Trend (EMA8 vs EMA21)
        double ema8 = computeEMA(candles, 8);
        double ema21 = computeEMA(candles, 21);
        if (ema21 > 0) {
            double emaSpread = (ema8 - ema21) / ema21 * 100;
            scores[7] = normalize(emaSpread, -0.5, 0.5); // ±0.5% → 0-1
        }

        // F9: Gap from Previous Close
        BigDecimal prevClose = context.extra("prevClose", BigDecimal.class);
        if (prevClose == null && n >= 2) {
            prevClose = candles.get(n - 2).close();
        }
        if (prevClose != null && prevClose.compareTo(BigDecimal.ZERO) > 0) {
            double gap = close.subtract(prevClose).doubleValue() / prevClose.doubleValue() * 100;
            scores[8] = normalize(gap, -1.5, 1.5);
        }

        // F10: Volume-Price Trend (acceleration)
        if (n >= 6) {
            double roc3 = close.subtract(candles.get(n - 4).close()).doubleValue()
                    / candles.get(n - 4).close().doubleValue() * 100;
            double roc6 = close.subtract(candles.get(n - 7).close()).doubleValue()
                    / candles.get(n - 7).close().doubleValue() * 100;
            double acceleration = roc3 - roc6; // positive = accelerating up
            scores[9] = normalize(acceleration, -1.0, 1.0);
        }

        // F11: Consecutive Direction (streak)
        int streak = 0;
        boolean bullish = close.compareTo(open) > 0;
        for (int k = n - 1; k >= Math.max(0, n - 8); k--) {
            Candle c = candles.get(k);
            boolean cBull = c.close().compareTo(c.open()) > 0;
            if (cBull == bullish) streak++;
            else break;
        }
        if (bullish) {
            scores[10] = 0.5 + Math.min(streak, 5) / 10.0; // max 1.0
        } else {
            scores[10] = 0.5 - Math.min(streak, 5) / 10.0; // min 0.0
        }

        // F12: Intraday Pattern (morning momentum vs afternoon)
        if (istHour != null) {
            int totalMin = istHour * 60 + istMinute;
            if (totalMin < 11 * 60) {
                // Morning: momentum is more reliable
                scores[11] = 0.6 + scores[2] * 0.2; // boost RSI signal in morning
            } else {
                // Afternoon: fade is more likely, require stronger signals
                scores[11] = 0.3 + scores[2] * 0.15;
            }
        }

        // ---- Regime Detection ----
        String regime = detectRegime(atrPct, ema8, ema21);

        // ---- Adaptive Weights ----
        double[] weights = getWeights(regime);

        // ---- Compute Composite Score ----
        double compositeScore = 0;
        double totalWeight = 0;
        StringBuilder factorLog = new StringBuilder();
        String[] factorNames = {
            "VolMom", "PriceMom", "RSI", "VWAP", "ORB",
            "Body", "ATR", "EMATrend", "Gap", "VPT",
            "Streak", "Intraday"
        };

        for (int i = 0; i < 12; i++) {
            compositeScore += scores[i] * weights[i];
            totalWeight += weights[i];
            if (i < 5 || scores[i] > 0.7 || scores[i] < 0.3) {
                factorLog.append(String.format(" %s=%.2f", factorNames[i], scores[i]));
            }
        }
        compositeScore = totalWeight > 0 ? compositeScore / totalWeight : 0.5;

        // Agreement boost: if majority of factors agree on direction, boost score
        int bullishFactors = 0, bearishFactors = 0;
        for (int i = 0; i < 12; i++) {
            if (scores[i] > 0.6) bullishFactors++;
            else if (scores[i] < 0.4) bearishFactors++;
        }
        int agreement = Math.max(bullishFactors, bearishFactors);
        if (agreement >= 7) {
            double boost = (agreement - 6) * 0.03; // 0.03-0.18 boost
            if (bullishFactors > bearishFactors) compositeScore += boost;
            else compositeScore -= boost;
            // agreement logging removed for performance
        }

        // ---- Decision ----
        double threshold = regime.equals("HIGH_VOL") ? 0.51 : 0.48;
        double shortThreshold = regime.equals("HIGH_VOL") ? 0.47 : 0.50;

        // Log score for debugging (every symbol, first signal attempt per day)
        // log removed 
            context.symbol(), String.format("%.4f", compositeScore), regime,
            String.format("%.2f", threshold), String.format("%.2f", shortThreshold));

        Signal.Side side = null;
        if (compositeScore > threshold) {
            side = Signal.Side.BUY;
        } else if (compositeScore < shortThreshold) {
            side = Signal.Side.SELL;
        }

        if (side == null) return null;

        // ---- Position Sizing & SL/Target ----
        double confidence = side == Signal.Side.BUY
                ? (compositeScore - 0.5) * 2  // 0.62→0.24, 0.8→0.6
                : (0.5 - compositeScore) * 2;
        confidence = Math.max(0.1, Math.min(0.85, confidence + 0.15));

        // Dynamic SL based on ATR
        double slPct = Math.max(0.15, Math.min(0.8, atrPct * 1.2));
        // Dynamic target based on R:R (minimum 2:1, up to 3:1 for high confidence)
        double rrRatio = 2.2 + confidence; // 2.15 to 2.85
        double targetPct = slPct * rrRatio;

        BigDecimal sl, target;
        if (side == Signal.Side.BUY) {
            sl = close.multiply(BigDecimal.valueOf(1.0 - slPct / 100.0))
                    .setScale(2, RoundingMode.HALF_UP);
            target = close.multiply(BigDecimal.valueOf(1.0 + targetPct / 100.0))
                    .setScale(2, RoundingMode.HALF_UP);
        } else {
            sl = close.multiply(BigDecimal.valueOf(1.0 + slPct / 100.0))
                    .setScale(2, RoundingMode.HALF_UP);
            target = close.multiply(BigDecimal.valueOf(1.0 - targetPct / 100.0))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        if (sl.compareTo(close) >= 0 || target.compareTo(close) == 0) return null;
        if (side == Signal.Side.BUY && target.compareTo(close) <= 0) return null;
        if (side == Signal.Side.SELL && target.compareTo(close) >= 0) return null;

        String reason = String.format("AI_ENS %s sc=%.3f rg=%s atr=%.2f%% rr=%.1f%s",
                side, compositeScore, regime, atrPct, rrRatio, factorLog);

        log.info("AI Ens {} {} score={} regime={} conf={}", context.symbol(), side, String.format("%.4f", compositeScore), regime, String.format("%.2f", confidence));
        return new Signal(context.symbol(), side, close, sl, target, confidence, reason);
    }

    // --- Regime Detection ---
    private String detectRegime(double atrPct, double ema8, double ema21) {
        boolean emaBullish = ema8 > ema21 * 1.001;
        boolean emaBearish = ema8 < ema21 * 0.999;

        if (atrPct > 1.5) return "HIGH_VOL";
        if (atrPct < 0.25) return "LOW_VOL";
        if (emaBullish || emaBearish) return "TRENDING";
        return "RANGING";
    }

    // --- Adaptive Weights per Regime ---
    private double[] getWeights(String regime) {
        return switch (regime) {
            case "TRENDING" -> new double[]{
                1.5, 2.0, 1.2, 0.8, 1.5,  // VolMom, PriceMom↑, RSI, VWAP↓, ORB
                1.0, 0.8, 2.0, 0.5, 1.5,   // Body, ATR↓, EMA↑, Gap↓, VPT
                1.2, 0.8                    // Streak, Intraday↓
            };
            case "RANGING" -> new double[]{
                0.8, 0.5, 1.0, 2.0, 0.5,   // VWAP↑, momentum↓
                1.5, 1.0, 0.5, 1.5, 0.8,   // Body↑, mean-reversion
                0.5, 1.0, 1.2               // Streak↓, Intraday↑
            };
            case "HIGH_VOL" -> new double[]{
                0.8, 1.8, 1.0, 0.3, 0.8,
                1.5, 2.5, 1.8, 0.5, 0.8,
                1.0, 0.3
            };
            default -> new double[]{ // RANGING / default
                1.0, 1.0, 1.0, 1.0, 1.0,
                1.0, 1.0, 1.0, 1.0, 1.0,
                1.0, 1.0
            };
        };
    }

    // --- Helpers ---
    private double normalize(double value, double min, double max) {
        if (max == min) return 0.5;
        double norm = (value - min) / (max - min);
        return Math.max(0, Math.min(1, norm));
    }

    private double computeATRPct(List<Candle> candles, int period, BigDecimal currentPrice) {
        int n = candles.size();
        if (n < period + 1 || currentPrice.compareTo(BigDecimal.ZERO) <= 0) return 0.5;

        double atrSum = 0;
        for (int k = n - period; k < n; k++) {
            Candle c = candles.get(k);
            double tr = c.high().subtract(c.low()).doubleValue();
            if (k > 0) {
                Candle prev = candles.get(k - 1);
                tr = Math.max(tr, Math.abs(c.high().doubleValue() - prev.close().doubleValue()));
                tr = Math.max(tr, Math.abs(c.low().doubleValue() - prev.close().doubleValue()));
            }
            atrSum += tr;
        }
        double atr = atrSum / period;
        return atr / currentPrice.doubleValue() * 100;
    }

    private double computeEMA(List<Candle> candles, int period) {
        int n = candles.size();
        if (n < period) return candles.get(n - 1).close().doubleValue();

        double multiplier = 2.0 / (period + 1);
        double ema = candles.get(n - period).close().doubleValue();
        for (int k = n - period + 1; k < n; k++) {
            double price = candles.get(k).close().doubleValue();
            ema = (price - ema) * multiplier + ema;
        }
        return ema;
    }
}
