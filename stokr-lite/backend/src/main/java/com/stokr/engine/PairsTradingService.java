package com.stokr.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

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
        double entrySpreadPct, double exitSpreadPct,
        String direction,   // "SHORT_A_LONG_B" | "LONG_A_SHORT_B"
        String exitReason,  // "SPREAD_REVERSION" | "SPREAD_STOP" | "EOD_EXIT"
        double legAGross, double legBGross,
        double netPnl, double brokerage
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pair",            symbolA + "/" + symbolB);
            m.put("symbolA",         symbolA);
            m.put("symbolB",         symbolB);
            m.put("direction",       direction);
            m.put("entryTime",       entryTime != null ? entryTime.toString() : null);
            m.put("exitTime",        exitTime  != null ? exitTime.toString()  : null);
            m.put("entryA",          r2(entryA)); m.put("entryB", r2(entryB));
            m.put("exitA",           r2(exitA));  m.put("exitB",  r2(exitB));
            m.put("entryZScore",     r2(entrySpreadPct));   // keep key name for UI compatibility
            m.put("exitZScore",      r2(exitSpreadPct));
            m.put("entrySpreadPct",  r2(entrySpreadPct));
            m.put("exitSpreadPct",   r2(exitSpreadPct));
            m.put("legAGross",       r2(legAGross));
            m.put("legBGross",       r2(legBGross));
            m.put("netPnl",          r2(netPnl));
            m.put("brokerage",       r2(brokerage));
            m.put("exitReason",      exitReason);
            return m;
        }
        private static double r2(double v) { return Math.round(v * 100.0) / 100.0; }
    }

    /**
     * Backtest a pair using the DAILY ANCHOR SPREAD method.
     *
     * Each day's opening ratio (9:15am) is the fair-value anchor.
     * Spread% = (current_ratio - anchor_ratio) / anchor_ratio * 100
     *
     * Entry:  spread > +entryPct (A expensive → short A, long B)
     *      OR spread < -entryPct (B expensive → long A, short B)
     * Exit:   spread reverts to within ±exitPct of zero
     * Stop:   |spread| > stopPct (relationship broke for today)
     *
     * @param entryPct  % deviation from daily open ratio to enter (default 1.5)
     * @param exitPct   % reversion back toward anchor to exit (default 0.2)
     * @param stopPct   % stop loss on spread (default 3.0)
     */
    public List<PairsTradeResult> backtestPair(
        String symbolA, String symbolB,
        List<CandleData> candlesA, List<CandleData> candlesB,
        int zWindow,   // kept for API compatibility, not used in spread mode
        double entryPct, double exitPct, double stopPct,
        double brokeragePer
    ) {
        // Align on timestamp
        Map<LocalDateTime, CandleData> mapB = new HashMap<>();
        for (CandleData c : candlesB) mapB.put(c.getTimestamp(), c);

        List<double[]> aligned     = new ArrayList<>();  // [closeA, closeB]
        List<LocalDateTime> timestamps = new ArrayList<>();
        for (CandleData ca : candlesA) {
            CandleData cb = mapB.get(ca.getTimestamp());
            if (cb == null) continue;
            aligned.add(new double[]{ca.getClose().doubleValue(), cb.getClose().doubleValue()});
            timestamps.add(ca.getTimestamp());
        }

        int n = aligned.size();
        if (n < 20) return List.of();

        // Compute daily opening ratio anchors (9:15am candle for each date)
        Map<LocalDate, Double> dailyAnchor = new HashMap<>();
        for (int i = 0; i < n; i++) {
            LocalDate d = timestamps.get(i).atZone(IST).toLocalDate();
            int h = timestamps.get(i).atZone(IST).getHour();
            int m = timestamps.get(i).atZone(IST).getMinute();
            // First candle of the day (9:15am) is the anchor
            if (!dailyAnchor.containsKey(d) && h == 9 && m == 15) {
                double[] a = aligned.get(i);
                if (a[1] > 0) dailyAnchor.put(d, a[0] / a[1]);
            }
        }

        // Compute spread% for each candle: (ratio - anchor) / anchor * 100
        double[] spreadPct = new double[n];
        for (int i = 0; i < n; i++) {
            LocalDate d = timestamps.get(i).atZone(IST).toLocalDate();
            Double anchor = dailyAnchor.get(d);
            if (anchor == null || anchor == 0) { spreadPct[i] = 0; continue; }
            double ratio = aligned.get(i)[0] / aligned.get(i)[1];
            spreadPct[i] = (ratio - anchor) / anchor * 100.0;
        }

        List<PairsTradeResult> trades = new ArrayList<>();
        boolean   inTrade   = false;
        String    direction = null;
        int       entryIdx  = -1;
        LocalDate entryDate = null;

        for (int i = 0; i < n; i++) {
            LocalDateTime ts = timestamps.get(i);
            LocalDate date   = ts.atZone(IST).toLocalDate();
            int h = ts.atZone(IST).getHour();
            int m = ts.atZone(IST).getMinute();
            int minOfDay = h * 60 + m;

            // If no anchor for this day, skip
            if (!dailyAnchor.containsKey(date)) continue;

            // ---- Force EOD exit ----
            if (inTrade && (minOfDay >= 15 * 60 + 5 || !date.equals(entryDate))) {
                int refIdx = !date.equals(entryDate) ? (i - 1) : i;
                if (refIdx >= 0 && refIdx < n) {
                    trades.add(buildTrade(symbolA, symbolB, direction,
                        timestamps.get(entryIdx), timestamps.get(refIdx),
                        aligned.get(entryIdx), aligned.get(refIdx),
                        spreadPct[entryIdx], spreadPct[refIdx], "EOD_EXIT", brokeragePer));
                }
                inTrade = false; direction = null; entryIdx = -1; entryDate = null;
            }

            // Entry window: 9:30 to 14:30 IST (skip first 15 min and last hour)
            if (!inTrade && (minOfDay < 9 * 60 + 30 || minOfDay > 14 * 60 + 30)) continue;

            if (!inTrade) {
                // A expensive relative to today's open → short A, long B
                if (spreadPct[i] >= entryPct) {
                    inTrade = true; direction = "SHORT_A_LONG_B";
                    entryIdx = i; entryDate = date;
                // B expensive relative to today's open → long A, short B
                } else if (spreadPct[i] <= -entryPct) {
                    inTrade = true; direction = "LONG_A_SHORT_B";
                    entryIdx = i; entryDate = date;
                }
            } else {
                double sp = spreadPct[i];
                boolean hitStop = Math.abs(sp) >= stopPct;
                // Reversion: spread has collapsed back toward zero (within ±exitPct of anchor)
                boolean hitTarget = "SHORT_A_LONG_B".equals(direction)
                    ? sp <= exitPct      // was positive (A expensive), now near zero or negative
                    : sp >= -exitPct;    // was negative (B expensive), now near zero or positive

                if (hitTarget || hitStop) {
                    String reason = hitStop ? "SPREAD_STOP" : "SPREAD_REVERSION";
                    trades.add(buildTrade(symbolA, symbolB, direction,
                        timestamps.get(entryIdx), ts,
                        aligned.get(entryIdx), aligned.get(i),
                        spreadPct[entryIdx], sp, reason, brokeragePer));
                    inTrade = false; direction = null; entryIdx = -1; entryDate = null;
                }
            }
        }

        log.info("Pair {}/{}: {} aligned candles, {} anchor days, {} trades",
            symbolA, symbolB, n, dailyAnchor.size(), trades.size());
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
        double entrySpread, double exitSpread,
        String exitReason, double brokerage
    ) {
        double eA = entry[0], eB = entry[1];
        double xA = exit[0],  xB = exit[1];
        double legAPnl, legBPnl;

        if ("SHORT_A_LONG_B".equals(direction)) {
            legAPnl = (eA - xA) / eA * CAPITAL_PER_LEG;  // short A: profit when A falls
            legBPnl = (xB - eB) / eB * CAPITAL_PER_LEG;  // long  B: profit when B rises
        } else {
            legAPnl = (xA - eA) / eA * CAPITAL_PER_LEG;  // long  A: profit when A rises
            legBPnl = (eB - xB) / eB * CAPITAL_PER_LEG;  // short B: profit when B falls
        }

        return new PairsTradeResult(symbolA, symbolB, entryTime, exitTime,
            eA, eB, xA, xB, entrySpread, exitSpread,
            direction, exitReason, legAPnl, legBPnl, legAPnl + legBPnl - brokerage, brokerage);
    }
}
