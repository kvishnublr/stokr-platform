package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Slf4j
@Component
public class AIEnsembleIntradayStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "AI_ENSEMBLE"; }

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

        Integer istHour = context.extra("istHour", Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int totalMin = istHour * 60 + istMinute;
            if (totalMin < 9 * 60 + 20 || totalMin > 14 * 60 + 45) return null;
        }

        double[] scores = new double[12];

        // F1: Volume Momentum
        long volSum = 0;
        int volLen = Math.min(10, n);
        for (int k = n - volLen; k < n; k++) volSum += candles.get(k).volume();
        long avgVol = volLen > 0 ? volSum / volLen : 1;
        double volRatio = avgVol > 0 ? (double) latest.volume() / avgVol : 1.0;
        scores[0] = normalize(volRatio, 0.5, 4.0);

        // F2: Price Momentum (tighter range for more decisive signal)
        int rocLen = Math.min(5, n);
        BigDecimal prevPrice = candles.get(n - rocLen).close();
        if (prevPrice.compareTo(BigDecimal.ZERO) > 0) {
            double roc = close.subtract(prevPrice).doubleValue() / prevPrice.doubleValue() * 100;
            scores[1] = normalize(roc, -1.0, 1.0);
        }

        // F3: RSI proximity (wider range from 0.5 center)
        BigDecimal rsi = context.indicators() != null ? context.indicators().get("RSI14") : null;
        if (rsi == null) rsi = context.extra("rsi14", BigDecimal.class);
        if (rsi != null) {
            double rsiVal = rsi.doubleValue();
            scores[2] = 0.5 + (rsiVal - 50) / 80.0;
            scores[2] = Math.max(0.0, Math.min(1.0, scores[2]));
        }

        // F4: VWAP Deviation
        BigDecimal vwap = context.vwap();
        if (vwap != null && vwap.compareTo(BigDecimal.ZERO) > 0) {
            double vwapDev = close.subtract(vwap).doubleValue() / vwap.doubleValue() * 100;
            scores[3] = normalize(vwapDev, -1.0, 1.0);
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

        // F6: Candle Body Ratio
        BigDecimal range = latest.high().subtract(latest.low());
        if (range.compareTo(BigDecimal.ZERO) > 0) {
            double bodyPct = close.subtract(open).abs().doubleValue() / range.doubleValue();
            if (close.compareTo(open) > 0) {
                scores[5] = 0.5 + bodyPct * 0.5;
            } else {
                scores[5] = 0.5 - bodyPct * 0.5;
            }
        }

        // F7: ATR Volatility
        double atrPct = computeATRPct(candles, 14, close);
        scores[6] = normalize(atrPct, 0.1, 2.0);

        // F8: EMA Trend
        double ema8 = computeEMA(candles, 8);
        double ema21 = computeEMA(candles, 21);
        if (ema21 > 0) {
            double emaSpread = (ema8 - ema21) / ema21 * 100;
            scores[7] = normalize(emaSpread, -0.5, 0.5);
        }

        // F9: Gap from Previous Close
        BigDecimal prevClose = context.extra("prevClose", BigDecimal.class);
        if (prevClose == null && n >= 2) prevClose = candles.get(n - 2).close();
        if (prevClose != null && prevClose.compareTo(BigDecimal.ZERO) > 0) {
            double gap = close.subtract(prevClose).doubleValue() / prevClose.doubleValue() * 100;
            scores[8] = normalize(gap, -1.5, 1.5);
        }

        // F10: Volume-Price Trend
        if (n >= 6) {
            double roc3 = close.subtract(candles.get(n - 4).close()).doubleValue()
                    / candles.get(n - 4).close().doubleValue() * 100;
            double roc6 = close.subtract(candles.get(n - 7).close()).doubleValue()
                    / candles.get(n - 7).close().doubleValue() * 100;
            scores[9] = normalize(roc3 - roc6, -1.0, 1.0);
        }

        // F11: Consecutive Direction
        int streak = 0;
        boolean bullish = close.compareTo(open) > 0;
        for (int k = n - 1; k >= Math.max(0, n - 8); k--) {
            boolean cBull = candles.get(k).close().compareTo(candles.get(k).open()) > 0;
            if (cBull == bullish) streak++;
            else break;
        }
        scores[10] = bullish ? 0.5 + Math.min(streak, 5) / 10.0 : 0.5 - Math.min(streak, 5) / 10.0;

        // F12: Intraday Pattern
        if (istHour != null) {
            int totalMin = istHour * 60 + istMinute;
            if (totalMin < 11 * 60) {
                scores[11] = 0.6 + scores[2] * 0.2;
            } else {
                scores[11] = 0.3 + scores[2] * 0.15;
            }
        }

        // Regime Detection
        String regime = detectRegime(atrPct, ema8, ema21);
        double[] weights = getWeights(regime);

        // Composite Score
        double compositeScore = 0;
        double totalWeight = 0;
        for (int i = 0; i < 12; i++) {
            compositeScore += scores[i] * weights[i];
            totalWeight += weights[i];
        }
        compositeScore = totalWeight > 0 ? compositeScore / totalWeight : 0.5;

        // Agreement boost
        int bullFactors = 0, bearFactors = 0;
        for (int i = 0; i < 12; i++) {
            if (scores[i] > 0.6) bullFactors++;
            else if (scores[i] < 0.4) bearFactors++;
        }
        int agreement = Math.max(bullFactors, bearFactors);
        if (agreement >= 7) {
            double boost = (agreement - 6) * 0.03;
            if (bullFactors > bearFactors) compositeScore += boost;
            else compositeScore -= boost;
        }

        // Decision thresholds
        double threshold = regime.equals("HIGH_VOL") ? 0.53 : 0.50;
        double shortThreshold = regime.equals("HIGH_VOL") ? 0.47 : 0.50;

        Signal.Side side = null;
        if (compositeScore > threshold) side = Signal.Side.BUY;
        else if (compositeScore < shortThreshold) side = Signal.Side.SELL;

        if (side == null) return null;

        // Position sizing
        double confidence = side == Signal.Side.BUY
                ? (compositeScore - 0.5) * 2 : (0.5 - compositeScore) * 2;
        confidence = Math.max(0.1, Math.min(0.85, confidence + 0.15));

        // Dynamic SL & Target
        double slPct = Math.max(0.15, Math.min(0.8, atrPct * 1.2));
        double rrRatio = 2.2 + confidence;
        double targetPct = slPct * rrRatio;

        BigDecimal sl, target;
        if (side == Signal.Side.BUY) {
            sl = close.multiply(BigDecimal.valueOf(1.0 - slPct / 100.0)).setScale(2, RoundingMode.HALF_UP);
            target = close.multiply(BigDecimal.valueOf(1.0 + targetPct / 100.0)).setScale(2, RoundingMode.HALF_UP);
        } else {
            sl = close.multiply(BigDecimal.valueOf(1.0 + slPct / 100.0)).setScale(2, RoundingMode.HALF_UP);
            target = close.multiply(BigDecimal.valueOf(1.0 - targetPct / 100.0)).setScale(2, RoundingMode.HALF_UP);
        }

        if (sl.compareTo(close) >= 0 || target.compareTo(close) == 0) return null;
        if (side == Signal.Side.BUY && target.compareTo(close) <= 0) return null;
        if (side == Signal.Side.SELL && target.compareTo(close) >= 0) return null;

        String reason = String.format("AI_ENS %s sc=%.3f rg=%s atr=%.2f%% rr=%.1f",
                side, compositeScore, regime, atrPct, rrRatio);

        log.debug("AI Ens {} {} score={} regime={}", context.symbol(), side, compositeScore, regime);
        return new Signal(context.symbol(), side, close, sl, target, confidence, reason);
    }

    private String detectRegime(double atrPct, double ema8, double ema21) {
        boolean emaBullish = ema8 > ema21 * 1.001;
        boolean emaBearish = ema8 < ema21 * 0.999;
        if (atrPct > 1.5) return "HIGH_VOL";
        if (atrPct < 0.25) return "LOW_VOL";
        if (emaBullish || emaBearish) return "TRENDING";
        return "RANGING";
    }

    private double[] getWeights(String regime) {
        return switch (regime) {
            case "TRENDING" -> new double[]{
                1.5, 2.0, 1.2, 0.8, 1.5,
                1.0, 0.8, 2.0, 0.5, 1.5,
                1.2, 0.8
            };
            case "RANGING" -> new double[]{
                0.8, 0.5, 1.0, 2.0, 0.5,
                1.5, 1.0, 0.5, 1.5, 0.8,
                0.5, 1.2
            };
            case "HIGH_VOL" -> new double[]{
                1.0, 1.5, 0.8, 0.5, 1.0,
                1.2, 2.0, 1.5, 0.8, 1.0,
                0.8, 0.5
            };
            default -> new double[]{1,1,1,1,1, 1,1,1,1,1, 1,1};
        };
    }

    private double normalize(double value, double min, double max) {
        if (max == min) return 0.5;
        return Math.max(0, Math.min(1, (value - min) / (max - min)));
    }

    private double computeATRPct(List<Candle> candles, int period, BigDecimal currentPrice) {
        int n = candles.size();
        if (n < period + 1 || currentPrice.compareTo(BigDecimal.ZERO) <= 0) return 0.5;
        double atrSum = 0;
        for (int k = n - period; k < n; k++) {
            Candle c = candles.get(k);
            double tr = c.high().subtract(c.low()).doubleValue();
            if (k > 0) {
                Candle p = candles.get(k - 1);
                tr = Math.max(tr, Math.abs(c.high().doubleValue() - p.close().doubleValue()));
                tr = Math.max(tr, Math.abs(c.low().doubleValue() - p.close().doubleValue()));
            }
            atrSum += tr;
        }
        return atrSum / period / currentPrice.doubleValue() * 100;
    }

    private double computeEMA(List<Candle> candles, int period) {
        int n = candles.size();
        if (n < period) return candles.get(n - 1).close().doubleValue();
        double mult = 2.0 / (period + 1);
        double ema = candles.get(n - period).close().doubleValue();
        for (int k = n - period + 1; k < n; k++) {
            ema = (candles.get(k).close().doubleValue() - ema) * mult + ema;
        }
        return ema;
    }
}
