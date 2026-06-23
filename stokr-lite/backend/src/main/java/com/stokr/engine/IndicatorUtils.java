package com.stokr.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class IndicatorUtils {

    public static record Indicators(
        BigDecimal vwap,
        BigDecimal rsi14,
        BigDecimal atr14,
        BigDecimal volSma10
    ) {}

    public static List<Indicators> computeAll(List<CandleData> candles) {
        int n = candles.size();
        List<Indicators> result = new ArrayList<>(n);

        BigDecimal[] vwaps = computeVwap(candles);
        BigDecimal[] rsis = computeRsi(candles, 14);
        BigDecimal[] atrs = computeAtr(candles, 14);
        BigDecimal[] volSmas = computeVolSma(candles, 10);

        for (int i = 0; i < n; i++) {
            result.add(new Indicators(
                vwaps[i],
                rsis[i],
                atrs[i],
                volSmas[i]
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
}
