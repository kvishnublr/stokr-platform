package com.stokr.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class IndicatorUtils {

    public static record Indicators(
        BigDecimal vwap,
        BigDecimal rsi14,
        BigDecimal atr14,
        BigDecimal volSma10,
        BigDecimal adx14
    ) {}

    public static List<Indicators> computeAll(List<CandleData> candles) {
        int n = candles.size();
        List<Indicators> result = new ArrayList<>(n);

        BigDecimal[] vwaps = computeVwap(candles);
        BigDecimal[] rsis = computeRsi(candles, 14);
        BigDecimal[] atrs = computeAtr(candles, 14);
        BigDecimal[] volSmas = computeVolSma(candles, 10);
        BigDecimal[] adxs = computeAdx(candles, 14);

        for (int i = 0; i < n; i++) {
            result.add(new Indicators(
                vwaps[i],
                rsis[i],
                atrs[i],
                volSmas[i],
                adxs[i]
            ));
        }
        return result;
    }

    public static BigDecimal[] computeVwap(List<CandleData> candles) {
        int n = candles.size();
        BigDecimal[] result = new BigDecimal[n];
        BigDecimal cumTpv = BigDecimal.ZERO;
        long cumVol = 0;

        for (int i = 0; i < n; i++) {
            CandleData c = candles.get(i);
            BigDecimal typical = c.getHigh().add(c.getLow()).add(c.getClose())
                .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            cumTpv = cumTpv.add(typical.multiply(BigDecimal.valueOf(c.getVolume())));
            cumVol += c.getVolume();
            result[i] = cumVol > 0
                ? cumTpv.divide(BigDecimal.valueOf(cumVol), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        }
        return result;
    }

    public static BigDecimal[] computeRsi(List<CandleData> candles, int period) {
        int n = candles.size();
        BigDecimal[] result = new BigDecimal[n];
        Arrays.fill(result, null);

        if (n < period + 1) return result;

        double avgGain = 0, avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double diff = candles.get(i).getClose().subtract(candles.get(i - 1).getClose()).doubleValue();
            if (diff > 0) avgGain += diff;
            else avgLoss += Math.abs(diff);
        }
        avgGain /= period;
        avgLoss /= period;
        result[period] = rsiValue(avgGain, avgLoss);

        for (int i = period + 1; i < n; i++) {
            double diff = candles.get(i).getClose().subtract(candles.get(i - 1).getClose()).doubleValue();
            avgGain = (avgGain * (period - 1) + (diff > 0 ? diff : 0)) / period;
            avgLoss = (avgLoss * (period - 1) + (diff < 0 ? Math.abs(diff) : 0)) / period;
            result[i] = rsiValue(avgGain, avgLoss);
        }
        return result;
    }

    private static BigDecimal rsiValue(double avgGain, double avgLoss) {
        if (avgLoss == 0) return BigDecimal.valueOf(100);
        double rs = avgGain / avgLoss;
        return BigDecimal.valueOf(100 - 100 / (1 + rs))
            .setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal[] computeAtr(List<CandleData> candles, int period) {
        int n = candles.size();
        BigDecimal[] result = new BigDecimal[n];
        Arrays.fill(result, null);

        if (n < 2) return result;

        List<BigDecimal> trs = new ArrayList<>(n);
        trs.add(null);
        for (int i = 1; i < n; i++) {
            CandleData c = candles.get(i);
            CandleData p = candles.get(i - 1);
            BigDecimal hl = c.getHigh().subtract(c.getLow());
            BigDecimal hcp = c.getHigh().subtract(p.getClose()).abs();
            BigDecimal lcp = c.getLow().subtract(p.getClose()).abs();
            BigDecimal tr = hl.max(hcp).max(lcp);
            trs.add(tr);
        }

        if (n < period + 1) return result;

        BigDecimal atr = BigDecimal.ZERO;
        for (int i = 1; i <= period; i++) {
            atr = atr.add(trs.get(i));
        }
        atr = atr.divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
        result[period] = atr;

        for (int i = period + 1; i < n; i++) {
            atr = atr.multiply(BigDecimal.valueOf(period - 1))
                .add(trs.get(i))
                .divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
            result[i] = atr;
        }
        return result;
    }

    public static BigDecimal[] computeVolSma(List<CandleData> candles, int period) {
        int n = candles.size();
        BigDecimal[] result = new BigDecimal[n];
        Arrays.fill(result, BigDecimal.ZERO);

        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += candles.get(i).getVolume();
            if (i >= period) sum -= candles.get(i - period).getVolume();
            if (i >= period - 1) {
                result[i] = BigDecimal.valueOf(sum)
                    .divide(BigDecimal.valueOf(period), 0, RoundingMode.HALF_UP);
            }
        }
        return result;
    }

    /**
     * Compute ADX(14) — Average Directional Index.
     * Measures trend strength: ADX > 25 = trending, ADX < 20 = range-bound.
     */
    public static BigDecimal[] computeAdx(List<CandleData> candles, int period) {
        int n = candles.size();
        BigDecimal[] result = new BigDecimal[n];
        Arrays.fill(result, null);

        if (n < period * 2 + 1) return result;

        double[] plusDm = new double[n];
        double[] minusDm = new double[n];
        double[] tr = new double[n];

        for (int i = 1; i < n; i++) {
            CandleData c = candles.get(i);
            CandleData p = candles.get(i - 1);
            double upMove = c.getHigh().doubleValue() - p.getHigh().doubleValue();
            double downMove = p.getLow().doubleValue() - c.getLow().doubleValue();
            plusDm[i] = (upMove > downMove && upMove > 0) ? upMove : 0;
            minusDm[i] = (downMove > upMove && downMove > 0) ? downMove : 0;

            double hl = c.getHigh().doubleValue() - c.getLow().doubleValue();
            double hpc = Math.abs(c.getHigh().doubleValue() - p.getClose().doubleValue());
            double lpc = Math.abs(c.getLow().doubleValue() - p.getClose().doubleValue());
            tr[i] = Math.max(hl, Math.max(hpc, lpc));
        }

        // Smoothed +DM, -DM, TR using Wilder's method
        double smPlusDm = 0, smMinusDm = 0, smTr = 0;
        for (int i = 1; i <= period; i++) {
            smPlusDm += plusDm[i];
            smMinusDm += minusDm[i];
            smTr += tr[i];
        }

        double[] dx = new double[n];
        for (int i = period; i < n; i++) {
            if (i > period) {
                smPlusDm = smPlusDm - smPlusDm / period + plusDm[i];
                smMinusDm = smMinusDm - smMinusDm / period + minusDm[i];
                smTr = smTr - smTr / period + tr[i];
            }

            double plusDi = smTr > 0 ? (smPlusDm / smTr) * 100 : 0;
            double minusDi = smTr > 0 ? (smMinusDm / smTr) * 100 : 0;
            double diSum = plusDi + minusDi;
            dx[i] = diSum > 0 ? Math.abs(plusDi - minusDi) / diSum * 100 : 0;
        }

        // ADX = smoothed DX over `period`
        int adxStart = period * 2;
        if (adxStart >= n) return result;

        double adxSum = 0;
        for (int i = period; i < adxStart; i++) adxSum += dx[i];
        double adx = adxSum / period;
        result[adxStart] = BigDecimal.valueOf(adx).setScale(2, RoundingMode.HALF_UP);

        for (int i = adxStart + 1; i < n; i++) {
            adx = (adx * (period - 1) + dx[i]) / period;
            result[i] = BigDecimal.valueOf(adx).setScale(2, RoundingMode.HALF_UP);
        }
        return result;
    }
}
