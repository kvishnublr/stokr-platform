package com.stokr.backtest;

import com.stokr.engine.CandleData;
import com.stokr.engine.CandleDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bollinger Squeeze Breakout — predicts sudden moves BEFORE they happen.
 *
 * 1. Bollinger Squeeze: BB width at N-period low (coiled spring → explosion incoming)
 * 2. Breakout: price breaks upper/lower band with volume confirmation
 * 3. Early exit: first range contraction after breakout (catches momentum, avoids reversal)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BollingerSqueezeService {

    private static final int BB_PERIOD = 20;
    private static final double BB_STD = 2.0;
    private static final int SQUEEZE_LOOKBACK = 50;
    private static final double VOL_SURGE_MIN = 1.5;
    private static final double STOP_LOSS = 0.005;
    private static final int MAX_HOLD = 5;
    private static final LocalTime START_TIME = LocalTime.of(9, 30);
    private static final LocalTime END_TIME = LocalTime.of(14, 45);
    private static final double CAPITAL = 12500.0;
    private static final double BROKERAGE = 0.002;
    private static final int MIN_GAP = 3;

    private final CandleDataRepository candleRepo;

    public BacktestReport runBacktest(String symbol, LocalDate start, LocalDate end) {
        List<CandleData> all = loadCandles(symbol, start, end);
        if (all.size() < 100) {
            return new BacktestReport(symbol, start, end, 0, "Insufficient data");
        }

        var byDay = all.stream().collect(Collectors.groupingBy(
            c -> c.getTimestamp().toLocalDate(), TreeMap::new, Collectors.toList()));

        List<TradeResult> trades = new ArrayList<>();
        int totalDays = 0;

        for (var e : byDay.entrySet()) {
            var dayCandles = e.getValue();
            if (dayCandles.size() < SQUEEZE_LOOKBACK + 20) continue;
            totalDays++;

            int n = dayCandles.size();
            double[] close = new double[n];
            double[] high = new double[n];
            double[] low = new double[n];
            long[] vol = new long[n];

            for (int i = 0; i < n; i++) {
                CandleData c = dayCandles.get(i);
                close[i] = c.getClose().doubleValue();
                high[i] = c.getHigh().doubleValue();
                low[i] = c.getLow().doubleValue();
                vol[i] = c.getVolume() != null ? c.getVolume() : 0;
            }

            // Bollinger Bands
            double[] sma = new double[n];
            double[] bandWidth = new double[n];
            double[] upper = new double[n];
            double[] lower = new double[n];

            for (int i = BB_PERIOD - 1; i < n; i++) {
                double sum = 0;
                for (int j = i - BB_PERIOD + 1; j <= i; j++) sum += close[j];
                sma[i] = sum / BB_PERIOD;

                double var = 0;
                for (int j = i - BB_PERIOD + 1; j <= i; j++) var += Math.pow(close[j] - sma[i], 2);
                double std = Math.sqrt(var / BB_PERIOD);

                upper[i] = sma[i] + BB_STD * std;
                lower[i] = sma[i] - BB_STD * std;
                bandWidth[i] = (upper[i] - lower[i]) / sma[i];
            }

            // Squeeze detection (rolling min of bandwidth)
            boolean[] squeeze = new boolean[n];
            for (int i = SQUEEZE_LOOKBACK + BB_PERIOD - 1; i < n; i++) {
                double minBW = Double.MAX_VALUE;
                for (int j = i - SQUEEZE_LOOKBACK + 1; j <= i; j++) {
                    if (bandWidth[j] < minBW) minBW = bandWidth[j];
                }
                squeeze[i] = bandWidth[i] <= minBW;
            }

            // Avg volume baseline
            double[] avgVol = new double[n];
            for (int i = 20; i < n; i++) {
                double sum = 0;
                for (int j = i - 20; j < i; j++) sum += vol[j];
                avgVol[i] = sum / 20;
            }

            boolean inTrade = false;
            boolean longDir = false;
            double entryPx = 0;
            int entryIdx = 0, qty = 0, cd = 0;
            boolean squeezeFired = false;
            int squeezeCandle = 0;

            for (int i = SQUEEZE_LOOKBACK + BB_PERIOD; i < n; i++) {
                LocalTime t = dayCandles.get(i).getTimestamp().toLocalTime();
                if (t.isBefore(START_TIME) || t.isAfter(END_TIME)) continue;

                if (squeeze[i] && !squeezeFired) {
                    // The moment BB squeeze happens — a move is coming
                    squeezeFired = true;
                    squeezeCandle = i;
                }

                // After a squeeze, wait for breakout
                if (squeezeFired && !inTrade) {
                    if (cd > 0) { cd--; continue; }

                    boolean breakout = false;

                    if (close[i] > upper[i] && vol[i] > avgVol[i] * VOL_SURGE_MIN) {
                        longDir = true;
                        breakout = true;
                    } else if (close[i] < lower[i] && vol[i] > avgVol[i] * VOL_SURGE_MIN) {
                        longDir = false;
                        breakout = true;
                    }

                    // Reset squeeze if no breakout within 15 candles
                    if (i - squeezeCandle > 15) {
                        squeezeFired = false;
                    }

                    if (breakout) {
                        entryIdx = i;
                        entryPx = close[i];
                        qty = Math.max(1, (int) (CAPITAL / entryPx));
                        inTrade = true;
                        squeezeFired = false;

                        // Calculate exit target: 1x ATR
                        double range = high[i] - low[i];
                        double target = longDir ? entryPx + range : entryPx - range;

                        TradeResult tr = new TradeResult();
                        tr.day = e.getKey();
                        tr.entryTime = dayCandles.get(i).getTimestamp();
                        tr.entryPrice = entryPx;
                        tr.direction = longDir ? "LONG" : "SHORT";
                        tr.quantity = qty;
                        tr.targetPrice = target;

                        // Check exit on next candles
                        boolean exited = false;
                        for (int k = i + 1; k < Math.min(n, i + MAX_HOLD + 1); k++) {
                            double exitPx = close[k];
                            String reason = null;
                            double move = longDir ? (exitPx - entryPx) / entryPx : (entryPx - exitPx) / entryPx;

                            // First range contraction = exit signal
                            double kRange = (high[k] - low[k]) / exitPx;
                            double entryRange = range / entryPx;
                            if (kRange < entryRange * 0.6 && k > i + 1) {
                                reason = "RANGE_CONTRACT";
                            }
                            // Hit target
                            else if ((longDir && exitPx >= target) || (!longDir && exitPx <= target)) {
                                reason = "TARGET";
                            }
                            // Stop loss
                            else if (move <= -STOP_LOSS) {
                                reason = "STOP";
                            }
                            // Max hold
                            else if (k - i >= MAX_HOLD) {
                                reason = "TIME";
                            }

                            if (reason != null) {
                                double gross = longDir
                                    ? (exitPx - entryPx) * qty
                                    : (entryPx - exitPx) * qty;
                                double brk = BROKERAGE * entryPx * qty;

                                tr.exitTime = dayCandles.get(k).getTimestamp();
                                tr.exitPrice = exitPx;
                                tr.exitReason = reason;
                                tr.grossPnL = gross;
                                tr.brokerage = brk;
                                tr.netPnL = gross - brk;
                                trades.add(tr);
                                exited = true;
                                inTrade = false;
                                cd = MIN_GAP;
                                break;
                            }
                        }

                        if (!exited) {
                            inTrade = false;
                            tr.exitTime = dayCandles.get(Math.min(i + MAX_HOLD, n - 1)).getTimestamp();
                            tr.exitPrice = dayCandles.get(Math.min(i + MAX_HOLD, n - 1)).getClose().doubleValue();
                            double gross = longDir
                                ? (tr.exitPrice - entryPx) * qty
                                : (entryPx - tr.exitPrice) * qty;
                            double brk = BROKERAGE * entryPx * qty;
                            tr.exitReason = "TIME";
                            tr.grossPnL = gross;
                            tr.brokerage = brk;
                            tr.netPnL = gross - brk;
                            trades.add(tr);
                            cd = MIN_GAP;
                        }
                    }
                }
            }
        }

        return buildReport(symbol, start, end, totalDays, all.size(), trades);
    }

    private BacktestReport buildReport(String symbol, LocalDate start, LocalDate end,
                                       int totalDays, int totalCandles, List<TradeResult> trades) {
        var r = new BacktestReport(symbol, start, end, totalDays, null);
        r.totalDays = totalDays;
        r.totalCandles = totalCandles;
        r.totalTrades = trades.size();

        int w = 0, l = 0;
        double g = 0, n = 0, b = 0, best = 0, worst = 0;
        for (TradeResult t : trades) {
            g += t.grossPnL; n += t.netPnL; b += t.brokerage;
            if (t.netPnL > 0) w++;
            else if (t.netPnL < 0) l++;
            if (t.netPnL > best) best = t.netPnL;
            if (t.netPnL < worst) worst = t.netPnL;
        }

        r.winCount = w; r.lossCount = l;
        r.winRate = trades.size() > 0 ? (double) w / trades.size() : 0;
        r.totalGrossPnL = g; r.totalNetPnL = n; r.totalBrokerage = b;
        r.avgNetPerTrade = trades.size() > 0 ? n / trades.size() : 0;
        r.anomaliesPerDay = totalDays > 0 ? (double) trades.size() / totalDays : 0;

        long lng = trades.stream().filter(t -> "LONG".equals(t.direction)).count();
        long shrt = trades.size() - lng;
        long tgtX = trades.stream().filter(t -> "TARGET".equals(t.exitReason)).count();
        long rcX = trades.stream().filter(t -> "RANGE_CONTRACT".equals(t.exitReason)).count();
        long stopX = trades.stream().filter(t -> "STOP".equals(t.exitReason)).count();
        long timeX = trades.stream().filter(t -> "TIME".equals(t.exitReason)).count();

        r.typeResults.add(tr("SUMMARY", trades.size(), r.winRate, g, n, b, best, worst));
        r.typeResults.add(tr("LONG", (int) lng, 0, 0, 0, 0, 0, 0));
        r.typeResults.add(tr("SHORT", (int) shrt, 0, 0, 0, 0, 0, 0));
        r.typeResults.add(tr("EXIT_TARGET", (int) tgtX, 0, 0, 0, 0, 0, 0));
        r.typeResults.add(tr("EXIT_RANGE_CONTRACT", (int) rcX, 0, 0, 0, 0, 0, 0));
        r.typeResults.add(tr("EXIT_STOP", (int) stopX, 0, 0, 0, 0, 0, 0));
        r.typeResults.add(tr("EXIT_TIME", (int) timeX, 0, 0, 0, 0, 0, 0));

        return r;
    }

    private BacktestReport.TypeRow tr(String t, int c, double wr, double g, double n, double b, double best, double worst) {
        var x = new BacktestReport.TypeRow();
        x.type = t; x.count = c; x.winRate = wr;
        x.grossPnL = g; x.netPnL = n; x.brokerage = b;
        x.bestTrade = best; x.worstTrade = worst;
        return x;
    }

    private List<CandleData> loadCandles(String symbol, LocalDate start, LocalDate end) {
        return candleRepo.findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
            symbol, "1min", start.atTime(9, 15), end.atTime(15, 30));
    }

    static class TradeResult {
        LocalDate day;
        LocalDateTime entryTime, exitTime;
        String direction, exitReason;
        double entryPrice, exitPrice, targetPrice;
        int quantity;
        double grossPnL, brokerage, netPnL;
    }

    public static class BacktestReport {
        public String symbol;
        public LocalDate startDate, endDate;
        public int totalDays, totalCandles, totalAnomalies;
        public double anomaliesPerDay;
        public int totalTrades, winCount, lossCount;
        public double winRate, totalGrossPnL, totalBrokerage, totalNetPnL, avgNetPerTrade;
        public final List<TypeRow> typeResults = new ArrayList<>();
        public String error;

        BacktestReport(String s, LocalDate sd, LocalDate ed, int td, String err) {
            symbol = s; startDate = sd; endDate = ed; totalDays = td; error = err;
        }

        public static class TypeRow {
            public String type;
            public int count;
            public double winRate, grossPnL, netPnL, brokerage, avgReturn, bestTrade, worstTrade;
        }
    }
}
