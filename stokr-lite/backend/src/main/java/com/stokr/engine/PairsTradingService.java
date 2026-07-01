package com.stokr.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Pairs Trading Service — Rolling Z-Score Mean Reversion.
 *
 * Methodology:
 *   logRatio(t) = log(closeA(t) / closeB(t))
 *   rollingMean / rollingStd over WINDOW candles (e.g. 300 = ~1.5 trading days)
 *   z(t) = (logRatio(t) - rollingMean) / rollingStd
 *
 * Entry:  |z| > Z_ENTRY  → spread is stretched, mean reversion expected
 * Exit:   |z| < Z_EXIT   → spread reverted to normal
 * Stop:   |z| > Z_STOP   → relationship broke, take the loss
 *
 * Improvement over daily-anchor spread:
 *   - Anchors to STATISTICAL history, not just today's 9:15 candle
 *   - Handles trending spreads properly (z-score auto-adjusts)
 *   - Allows multiple entries per day (vs single trade/day cap)
 *   - Correlation gate: skips days where rolling correlation < 0.75
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PairsTradingService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final double CAPITAL_PER_LEG = 25_000.0;

    // 16 pairs — all confirmed to have historical data in DB
    public static final List<String[]> DEFAULT_PAIRS = List.of(
        // Private banks (very high correlation)
        new String[]{"HDFCBANK",   "ICICIBANK"},
        new String[]{"KOTAKBANK",  "AXISBANK"},
        // Large-cap IT (high correlation)
        new String[]{"TCS",        "INFY"},
        new String[]{"WIPRO",      "HCLTECH"},
        new String[]{"INFY",       "HCLTECH"},
        // PSU banks (high correlation)
        new String[]{"SBIN",       "BANKBARODA"},
        // Pharma (high correlation)
        new String[]{"SUNPHARMA",  "DRREDDY"},
        new String[]{"CIPLA",      "LUPIN"},
        // PSU Power/Energy
        new String[]{"NTPC",       "POWERGRID"},
        new String[]{"RELIANCE",   "ONGC"},
        // Metals
        new String[]{"HINDALCO",   "TATASTEEL"},
        // Bajaj group financials (same promoter, very high correlation)
        new String[]{"BAJFINANCE", "BAJAJFINSV"},
        // Life Insurance
        new String[]{"HDFCLIFE",   "SBILIFE"},
        // Paints (near-duopoly, move together)
        new String[]{"ASIANPAINT", "BERGEPAINT"},
        // FMCG
        new String[]{"HINDUNILVR", "ITC"},
        // 2-wheelers
        new String[]{"BAJAJ-AUTO", "HEROMOTOCO"}
    );

    public record PairsTradeResult(
        String symbolA, String symbolB,
        LocalDateTime entryTime, LocalDateTime exitTime,
        double entryA, double entryB,
        double exitA,  double exitB,
        double entryZ, double exitZ,
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
            m.put("entryZScore",     r2(entryZ));
            m.put("exitZScore",      r2(exitZ));
            m.put("entrySpreadPct",  r2(entryZ));  // UI compat
            m.put("exitSpreadPct",   r2(exitZ));
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
     * Backtest a pair using rolling z-score mean reversion.
     *
     * @param zWindow   rolling window in candles for z-score (default 300 ≈ 1.5 days)
     * @param zEntry    z-score threshold to enter (default 2.0)
     * @param zExit     z-score threshold to exit / profit take (default 0.5)
     * @param zStop     z-score threshold for stop loss (default 3.5)
     */
    public List<PairsTradeResult> backtestPair(
        String symbolA, String symbolB,
        List<CandleData> candlesA, List<CandleData> candlesB,
        int zWindow, double zEntry, double zExit, double zStop,
        double brokeragePer
    ) {
        // ── Align on timestamp ────────────────────────────────────────────────
        Map<LocalDateTime, CandleData> mapB = new HashMap<>();
        for (CandleData c : candlesB) mapB.put(c.getTimestamp(), c);

        List<double[]>       aligned    = new ArrayList<>(); // [closeA, closeB]
        List<LocalDateTime>  timestamps = new ArrayList<>();
        for (CandleData ca : candlesA) {
            CandleData cb = mapB.get(ca.getTimestamp());
            if (cb == null) continue;
            double a = ca.getClose().doubleValue();
            double b = cb.getClose().doubleValue();
            if (a <= 0 || b <= 0) continue;
            aligned.add(new double[]{a, b});
            timestamps.add(ca.getTimestamp());
        }
        int n = aligned.size();
        if (n < zWindow + 20) return List.of();

        // ── Rolling z-score via Welford's online algorithm ────────────────────
        // logRatio = log(closeA / closeB) — symmetric, better statistics than raw ratio
        double[] logRatio = new double[n];
        for (int i = 0; i < n; i++) {
            logRatio[i] = Math.log(aligned.get(i)[0] / aligned.get(i)[1]);
        }

        double[] zScore = new double[n];
        ArrayDeque<Double> window = new ArrayDeque<>(zWindow + 1);
        double runSum = 0, runSumSq = 0;
        int minWarm = Math.max(60, zWindow / 3); // need at least 60 candles to start

        for (int i = 0; i < n; i++) {
            double v = logRatio[i];
            window.addLast(v);
            runSum   += v;
            runSumSq += v * v;
            if (window.size() > zWindow) {
                double removed = window.pollFirst();
                runSum   -= removed;
                runSumSq -= removed * removed;
            }
            int w = window.size();
            if (w < minWarm) { zScore[i] = 0; continue; }
            double mean = runSum / w;
            double var  = runSumSq / w - mean * mean;
            double std  = var > 1e-10 ? Math.sqrt(var) : 0;
            zScore[i]   = std > 0 ? (v - mean) / std : 0;
        }

        // ── Rolling correlation gate (200-candle window) ──────────────────────
        // Compute rolling correlation for risk gating (skip entry if correlation broken)
        int corrWindow = Math.min(200, n / 4);
        double[] rollCorr = new double[n];
        {
            ArrayDeque<double[]> cw = new ArrayDeque<>(corrWindow + 1);
            double sA=0,sB=0,sAA=0,sBB=0,sAB=0;
            for (int i = 0; i < n; i++) {
                double a = aligned.get(i)[0], b = aligned.get(i)[1];
                cw.addLast(new double[]{a, b});
                sA+=a; sB+=b; sAA+=a*a; sBB+=b*b; sAB+=a*b;
                if (cw.size() > corrWindow) {
                    double[] rem = cw.pollFirst();
                    sA-=rem[0]; sB-=rem[1]; sAA-=rem[0]*rem[0]; sBB-=rem[1]*rem[1]; sAB-=rem[0]*rem[1];
                }
                int cw2 = cw.size();
                if (cw2 < 30) { rollCorr[i] = 1.0; continue; }
                double mA=sA/cw2, mB=sB/cw2;
                double cov=(sAB/cw2)-mA*mB, vA=(sAA/cw2)-mA*mA, vB=(sBB/cw2)-mB*mB;
                rollCorr[i] = (vA>0&&vB>0) ? cov/Math.sqrt(vA*vB) : 0;
            }
        }

        // ── Trade simulation ──────────────────────────────────────────────────
        List<PairsTradeResult> trades = new ArrayList<>();
        boolean   inTrade   = false;
        String    direction = null;
        int       entryIdx  = -1;
        LocalDate entryDate = null;
        // Track entries per day to limit overtrading (max 2 per pair per day)
        Map<LocalDate, Integer> entriesPerDay = new HashMap<>();

        for (int i = 1; i < n; i++) {
            LocalDateTime ts   = timestamps.get(i);
            LocalDate     date = ts.atZone(IST).toLocalDate();
            int h = ts.atZone(IST).getHour();
            int m = ts.atZone(IST).getMinute();
            int minOfDay = h * 60 + m;

            // ── Force EOD exit at 15:05 or on date change ───────────────────
            if (inTrade && (minOfDay >= 15 * 60 + 5 || !date.equals(entryDate))) {
                int refIdx = !date.equals(entryDate) ? (i - 1) : i;
                if (refIdx >= 0) {
                    trades.add(buildTrade(symbolA, symbolB, direction,
                        timestamps.get(entryIdx), timestamps.get(refIdx),
                        aligned.get(entryIdx), aligned.get(refIdx),
                        zScore[entryIdx], zScore[refIdx], "EOD_EXIT", brokeragePer));
                }
                inTrade = false; direction = null; entryIdx = -1; entryDate = null;
            }

            // Entry window: 9:30 to 14:00 IST
            if (!inTrade && (minOfDay < 9 * 60 + 30 || minOfDay > 14 * 60)) continue;

            // Skip if z-score not yet reliable
            if (zScore[i] == 0) continue;

            double z  = zScore[i];
            double rc = rollCorr[i];

            if (!inTrade) {
                // Max 2 entries per pair per day to avoid overtrading
                int todayEntries = entriesPerDay.getOrDefault(date, 0);
                if (todayEntries >= 2) continue;

                // Correlation gate: don't trade if relationship broken
                if (rc < 0.70) continue;

                if (z >= zEntry) {
                    // A expensive relative to B history → short A, long B
                    inTrade = true; direction = "SHORT_A_LONG_B";
                    entryIdx = i; entryDate = date;
                    entriesPerDay.merge(date, 1, Integer::sum);
                } else if (z <= -zEntry) {
                    // B expensive relative to A history → long A, short B
                    inTrade = true; direction = "LONG_A_SHORT_B";
                    entryIdx = i; entryDate = date;
                    entriesPerDay.merge(date, 1, Integer::sum);
                }

            } else {
                boolean hitStop = Math.abs(z) >= zStop;
                boolean hitTarget = "SHORT_A_LONG_B".equals(direction)
                    ? z <= zExit          // spread compressed back
                    : z >= -zExit;

                if (hitTarget || hitStop) {
                    String reason = hitStop ? "SPREAD_STOP" : "SPREAD_REVERSION";
                    trades.add(buildTrade(symbolA, symbolB, direction,
                        timestamps.get(entryIdx), ts,
                        aligned.get(entryIdx), aligned.get(i),
                        zScore[entryIdx], z, reason, brokeragePer));
                    inTrade = false; direction = null; entryIdx = -1; entryDate = null;
                }
            }
        }

        log.info("Pair {}/{}: {} aligned candles, {} trades (zWindow={}, zEntry={}, zExit={}, zStop={})",
            symbolA, symbolB, n, trades.size(), zWindow, zEntry, zExit, zStop);
        return trades;
    }

    // ── Opening-Drift strategy ────────────────────────────────────────────────

    /**
     * Opening-Drift pairs backtest.
     *
     * Key insight: The daily close log-ratio distribution has ~5-10× more variance than
     * the intraday 300-candle distribution. At z=2.0 on daily history, the spread is
     * 3-5% rather than 0.3-0.4% — meaning P&L per trade is 10× larger, making
     * brokerage irrelevant.
     *
     * Method:
     *   1. Build 20-day rolling mean/std from daily CLOSE log-ratios (yesterday's close history)
     *   2. At 9:15 open, compute z = (log(openA/openB) - dailyMean) / dailyStd
     *   3. If |z| > zEntry, a pair has diverged overnight — enter at 9:30
     *   4. Monitor intraday: exit when z reverts to zExit, or stop at zStop
     */
    public List<PairsTradeResult> backtestPairOpeningDrift(
        String symbolA, String symbolB,
        List<CandleData> candlesA, List<CandleData> candlesB,
        int dailyWindow, double zEntry, double zEntryMax,
        double zExit, double zStop, double brokeragePer
    ) {
        // ── Align on timestamp ───────────────────────────────────────────────
        Map<LocalDateTime, CandleData> mapB = new HashMap<>();
        for (CandleData c : candlesB) mapB.put(c.getTimestamp(), c);

        // Group aligned candles by date
        TreeMap<LocalDate, List<double[]>> byDate = new TreeMap<>();
        TreeMap<LocalDate, List<LocalDateTime>> byDateTs = new TreeMap<>();

        for (CandleData ca : candlesA) {
            CandleData cb = mapB.get(ca.getTimestamp());
            if (cb == null) continue;
            double a = ca.getClose().doubleValue(), b = cb.getClose().doubleValue();
            if (a <= 0 || b <= 0) continue;
            LocalDateTime ts = ca.getTimestamp();
            LocalDate d = ts.toLocalDate(); // timestamps stored as IST LocalDateTime
            byDate.computeIfAbsent(d, k -> new ArrayList<>()).add(new double[]{a, b});
            byDateTs.computeIfAbsent(d, k -> new ArrayList<>()).add(ts);
        }

        List<LocalDate> dates = new ArrayList<>(byDate.keySet());
        if (dates.size() < dailyWindow + 2) {
            log.warn("OpeningDrift {}/{}: only {} days, need {}", symbolA, symbolB, dates.size(), dailyWindow + 2);
            return List.of();
        }

        // ── Extract daily close log-ratios ───────────────────────────────────
        // For each day: take the LAST candle (EOD close proxy ≈ 15:29)
        double[] dailyLogRatio = new double[dates.size()];
        for (int i = 0; i < dates.size(); i++) {
            List<double[]> day = byDate.get(dates.get(i));
            double[] last = day.get(day.size() - 1);
            dailyLogRatio[i] = Math.log(last[0] / last[1]);
        }

        // ── Trade each day from dailyWindow onward ───────────────────────────
        List<PairsTradeResult> trades = new ArrayList<>();

        for (int di = dailyWindow; di < dates.size(); di++) {
            LocalDate today = dates.get(di);

            // 20-day mean/std from PREVIOUS days only (di-dailyWindow to di-1)
            double sum = 0;
            for (int k = di - dailyWindow; k < di; k++) sum += dailyLogRatio[k];
            double mean = sum / dailyWindow;
            double sumSq = 0;
            for (int k = di - dailyWindow; k < di; k++) {
                double diff = dailyLogRatio[k] - mean;
                sumSq += diff * diff;
            }
            double std = sumSq > 1e-12 ? Math.sqrt(sumSq / dailyWindow) : 0;
            if (std < 1e-8) continue;

            List<double[]>       todayPrices = byDate.get(today);
            List<LocalDateTime>  todayTs     = byDateTs.get(today);
            if (todayPrices == null || todayPrices.size() < 5) continue;

            // Today's opening ratio (first candle = 9:15 auction result)
            double[] firstPx = todayPrices.get(0);
            double openLogRatio = Math.log(firstPx[0] / firstPx[1]);
            double openZ = (openLogRatio - mean) / std;

            if (Math.abs(openZ) < zEntry) continue;    // opening not extreme enough
            if (Math.abs(openZ) > zEntryMax) continue; // too extreme = real event, skip

            String direction = openZ >= zEntry ? "SHORT_A_LONG_B" : "LONG_A_SHORT_B";

            // Find entry candle: first candle at or after 9:30
            int entryIdx = -1;
            for (int ci = 0; ci < todayTs.size(); ci++) {
                LocalDateTime ts = todayTs.get(ci);
                int minOfDay = ts.getHour() * 60 + ts.getMinute();
                if (minOfDay >= 9 * 60 + 30) { entryIdx = ci; break; }
            }
            if (entryIdx < 0) continue;

            LocalDateTime  entryTime = todayTs.get(entryIdx);
            double[]       entryPx   = todayPrices.get(entryIdx);

            // Monitor intraday for exit
            int    exitIdx    = todayPrices.size() - 1;
            String exitReason = "EOD_EXIT";
            double exitZ      = (Math.log(todayPrices.get(exitIdx)[0] / todayPrices.get(exitIdx)[1]) - mean) / std;

            for (int ci = entryIdx + 1; ci < todayTs.size(); ci++) {
                LocalDateTime ts = todayTs.get(ci);
                int minOfDay = ts.getHour() * 60 + ts.getMinute();

                if (minOfDay >= 15 * 60 + 5) { // force EOD at 15:05
                    exitIdx = ci; exitReason = "EOD_EXIT";
                    exitZ = (Math.log(todayPrices.get(ci)[0] / todayPrices.get(ci)[1]) - mean) / std;
                    break;
                }

                double curLog = Math.log(todayPrices.get(ci)[0] / todayPrices.get(ci)[1]);
                double curZ   = (curLog - mean) / std;

                boolean hitTarget = "SHORT_A_LONG_B".equals(direction) ? curZ <= zExit : curZ >= -zExit;
                boolean hitStop   = Math.abs(curZ) >= zStop;

                if (hitTarget || hitStop) {
                    exitIdx = ci;
                    exitReason = hitStop ? "SPREAD_STOP" : "SPREAD_REVERSION";
                    exitZ = curZ;
                    break;
                }
            }

            LocalDateTime exitTime = todayTs.get(exitIdx);
            double[]      exitPx   = todayPrices.get(exitIdx);

            trades.add(buildTrade(symbolA, symbolB, direction,
                entryTime, exitTime, entryPx, exitPx,
                openZ, exitZ, exitReason, brokeragePer));
        }

        log.info("OpeningDrift {}/{}: {} days, {} trades (window={}, zEntry={}-{}, zExit={}, zStop={})",
            symbolA, symbolB, dates.size(), trades.size(), dailyWindow, zEntry, zEntryMax, zExit, zStop);
        return trades;
    }

    /** Pearson correlation on aligned closes (full period). */
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
        double sumA=0, sumB=0;
        for (double[] p : pts) { sumA+=p[0]; sumB+=p[1]; }
        double mA=sumA/n, mB=sumB/n, cov=0, vA=0, vB=0;
        for (double[] p : pts) {
            cov += (p[0]-mA)*(p[1]-mB);
            vA  += (p[0]-mA)*(p[0]-mA);
            vB  += (p[1]-mB)*(p[1]-mB);
        }
        return (vA>0&&vB>0) ? cov/Math.sqrt(vA*vB) : 0;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private PairsTradeResult buildTrade(
        String symbolA, String symbolB, String direction,
        LocalDateTime entryTime, LocalDateTime exitTime,
        double[] entry, double[] exit,
        double entryZ, double exitZ,
        String exitReason, double brokerage
    ) {
        double eA=entry[0], eB=entry[1], xA=exit[0], xB=exit[1];
        double legAPnl, legBPnl;
        if ("SHORT_A_LONG_B".equals(direction)) {
            legAPnl = (eA - xA) / eA * CAPITAL_PER_LEG;
            legBPnl = (xB - eB) / eB * CAPITAL_PER_LEG;
        } else {
            legAPnl = (xA - eA) / eA * CAPITAL_PER_LEG;
            legBPnl = (eB - xB) / eB * CAPITAL_PER_LEG;
        }
        return new PairsTradeResult(symbolA, symbolB, entryTime, exitTime,
            eA, eB, xA, xB, entryZ, exitZ,
            direction, exitReason, legAPnl, legBPnl, legAPnl + legBPnl - brokerage, brokerage);
    }
}
