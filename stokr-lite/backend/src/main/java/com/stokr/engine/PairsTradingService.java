package com.stokr.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PairsTradingService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final double CAPITAL_PER_LEG = 25_000.0;

    // Default pairs to backtest (all NSE, high co-integration)
    public static final List<String[]> DEFAULT_PAIRS = List.of(
        new String[]{"HDFCBANK",   "ICICIBANK"},
        new String[]{"TCS",        "INFY"},
        new String[]{"SBIN",       "AXISBANK"},
        new String[]{"WIPRO",      "HCLTECH"},
        new String[]{"BAJFINANCE", "BAJAJFINSV"},
        new String[]{"RELIANCE",   "ONGC"},
        new String[]{"KOTAKBANK",  "AXISBANK"},
        new String[]{"MARUTI",     "TATAMOTORS"}
    );

    public record PairsTradeResult(
        String symbolA, String symbolB,
        LocalDateTime entryTime, LocalDateTime exitTime,
        double entryA, double entryB,
        double exitA,  double exitB,
        double entryZScore, double exitZScore,
        String direction,  // "SHORT_A_LONG_B" | "LONG_A_SHORT_B"
        String exitReason, // "ZSCORE_REVERSION" | "ZSCORE_STOP" | "EOD_EXIT"
        double legAGross, double legBGross,
        double netPnl, double brokerage
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pair",        symbolA + "/" + symbolB);
            m.put("symbolA",     symbolA);
            m.put("symbolB",     symbolB);
            m.put("direction",   direction);
            m.put("entryTime",   entryTime != null ? entryTime.toString() : null);
            m.put("exitTime",    exitTime  != null ? exitTime.toString()  : null);
            m.put("entryA",      r2(entryA)); m.put("entryB", r2(entryB));
            m.put("exitA",       r2(exitA));  m.put("exitB",  r2(exitB));
            m.put("entryZScore", r2(entryZScore));
            m.put("exitZScore",  r2(exitZScore));
            m.put("legAGross",   r2(legAGross));
            m.put("legBGross",   r2(legBGross));
            m.put("netPnl",      r2(netPnl));
            m.put("brokerage",   r2(brokerage));
            m.put("exitReason",  exitReason);
            return m;
        }
        private static double r2(double v) { return Math.round(v * 100.0) / 100.0; }
    }

    /**
     * Backtest a single pair over the aligned candle data.
     *
     * @param zWindow  rolling window to compute mean/std of ratio (e.g. 20 = 20 candles)
     * @param zEntry   z-score threshold to enter (default 2.0)
     * @param zExit    z-score threshold to exit on reversion (default 0.3, near mean)
     * @param zStop    z-score stop loss — exit if spread widens further (default 3.5)
     */
    public List<PairsTradeResult> backtestPair(
        String symbolA, String symbolB,
        List<CandleData> candlesA, List<CandleData> candlesB,
        int zWindow, double zEntry, double zExit, double zStop,
        double brokeragePer
    ) {
        // Align on timestamp
        Map<LocalDateTime, CandleData> mapB = new HashMap<>();
        for (CandleData c : candlesB) mapB.put(c.getTimestamp(), c);

        List<double[]> aligned = new ArrayList<>();  // [closeA, closeB]
        List<LocalDateTime> timestamps = new ArrayList<>();
        for (CandleData ca : candlesA) {
            CandleData cb = mapB.get(ca.getTimestamp());
            if (cb == null) continue;
            aligned.add(new double[]{ca.getClose().doubleValue(), cb.getClose().doubleValue()});
            timestamps.add(ca.getTimestamp());
        }

        int n = aligned.size();
        if (n < zWindow + 10) return List.of();

        // Ratio series + rolling z-score
        double[] ratios  = new double[n];
        double[] zscores = new double[n];
        for (int i = 0; i < n; i++) ratios[i] = aligned.get(i)[0] / aligned.get(i)[1];

        for (int i = 0; i < n; i++) {
            if (i < zWindow) { zscores[i] = 0; continue; }
            double mean = 0, std = 0;
            for (int k = i - zWindow; k < i; k++) mean += ratios[k];
            mean /= zWindow;
            for (int k = i - zWindow; k < i; k++) {
                double d = ratios[k] - mean;
                std += d * d;
            }
            std = Math.sqrt(std / zWindow);
            zscores[i] = std < 1e-12 ? 0 : (ratios[i] - mean) / std;
        }

        List<PairsTradeResult> trades = new ArrayList<>();
        boolean inTrade   = false;
        String  direction = null;
        int     entryIdx  = -1;
        LocalDate entryDate = null;

        for (int i = zWindow; i < n; i++) {
            LocalDateTime ts = timestamps.get(i);
            LocalDate date   = ts.atZone(IST).toLocalDate();
            int h = ts.atZone(IST).getHour();
            int m = ts.atZone(IST).getMinute();
            int minOfDay = h * 60 + m;

            // ---- Force EOD exit ----
            if (inTrade && (minOfDay >= 15 * 60 + 5 || !date.equals(entryDate))) {
                int refIdx = !date.equals(entryDate) ? (i - 1) : i;
                PairsTradeResult t = buildTrade(symbolA, symbolB, direction,
                    timestamps.get(entryIdx), timestamps.get(refIdx),
                    aligned.get(entryIdx), aligned.get(refIdx),
                    zscores[entryIdx], zscores[refIdx], "EOD_EXIT", brokeragePer);
                trades.add(t);
                inTrade = false; direction = null; entryIdx = -1; entryDate = null;
            }

            // Only enter between 9:20 and 14:45 IST
            if (!inTrade && (minOfDay < 9 * 60 + 20 || minOfDay > 14 * 60 + 45)) continue;

            if (!inTrade) {
                if (zscores[i] >= zEntry) {
                    inTrade = true; direction = "SHORT_A_LONG_B";
                    entryIdx = i; entryDate = date;
                } else if (zscores[i] <= -zEntry) {
                    inTrade = true; direction = "LONG_A_SHORT_B";
                    entryIdx = i; entryDate = date;
                }
            } else {
                boolean hitStop   = Math.abs(zscores[i]) >= zStop;
                // For SHORT_A_LONG_B (z was positive), revert = z drops to ≤ +zExit
                // For LONG_A_SHORT_B  (z was negative), revert = z rises to ≥ -zExit
                boolean hitTarget = "SHORT_A_LONG_B".equals(direction)
                    ? zscores[i] <= zExit
                    : zscores[i] >= -zExit;

                if (hitTarget || hitStop) {
                    String reason = hitStop ? "ZSCORE_STOP" : "ZSCORE_REVERSION";
                    PairsTradeResult t = buildTrade(symbolA, symbolB, direction,
                        timestamps.get(entryIdx), ts,
                        aligned.get(entryIdx), aligned.get(i),
                        zscores[entryIdx], zscores[i], reason, brokeragePer);
                    trades.add(t);
                    inTrade = false; direction = null; entryIdx = -1; entryDate = null;
                }
            }
        }

        return trades;
    }

    /** Pearson correlation on aligned closes. */
    public double computeCorrelation(List<CandleData> candlesA, List<CandleData> candlesB) {
        Map<LocalDateTime, Double> mapB = new HashMap<>();
        for (CandleData c : candlesB) mapB.put(c.getTimestamp(), c.getClose().doubleValue());

        List<double[]> pts = new ArrayList<>();
        for (CandleData ca : candlesA) {
            Double cb = mapB.get(ca.getTimestamp());
            if (cb != null) pts.add(new double[]{ca.getClose().doubleValue(), cb});
        }
        int n = pts.size();
        if (n < 10) return 0;

        double sumA = 0, sumB = 0;
        for (double[] p : pts) { sumA += p[0]; sumB += p[1]; }
        double meanA = sumA / n, meanB = sumB / n;
        double cov = 0, varA = 0, varB = 0;
        for (double[] p : pts) {
            cov  += (p[0] - meanA) * (p[1] - meanB);
            varA += (p[0] - meanA) * (p[0] - meanA);
            varB += (p[1] - meanB) * (p[1] - meanB);
        }
        return (varA > 0 && varB > 0) ? cov / Math.sqrt(varA * varB) : 0;
    }

    // ---- private helpers ----

    private PairsTradeResult buildTrade(
        String symbolA, String symbolB, String direction,
        LocalDateTime entryTime, LocalDateTime exitTime,
        double[] entry, double[] exit,
        double entryZ, double exitZ,
        String exitReason, double brokerage
    ) {
        double eA = entry[0], eB = entry[1];
        double xA = exit[0],  xB = exit[1];
        double legAPnl, legBPnl;

        if ("SHORT_A_LONG_B".equals(direction)) {
            legAPnl = (eA - xA) / eA * CAPITAL_PER_LEG;  // short A
            legBPnl = (xB - eB) / eB * CAPITAL_PER_LEG;  // long  B
        } else {
            legAPnl = (xA - eA) / eA * CAPITAL_PER_LEG;  // long  A
            legBPnl = (eB - xB) / eB * CAPITAL_PER_LEG;  // short B
        }

        return new PairsTradeResult(symbolA, symbolB, entryTime, exitTime,
            eA, eB, xA, xB, entryZ, exitZ,
            direction, exitReason, legAPnl, legBPnl, legAPnl + legBPnl - brokerage, brokerage);
    }
}
