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
 * CUSUM strategy on 5-min candles.
 *
 * Detects sustained drift via cumulative sum of standardized returns.
 * Only trades 10:00-14:30 to avoid open/close noise.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CusumBacktestService {

    private static final double K = 0.25;
    private static final double H = 3.0;
    private static final double VOL_RATIO = 1.0;
    private static final int VOL_PERIODS = 12;
    private static final int BASELINE_PERIODS = 50;
    private static final int MAX_HOLD = 6;
    private static final int MIN_GAP = 1;
    private static final double STOP_LOSS = 0.006;
    private static final LocalTime START_TRADE = LocalTime.of(10, 0);
    private static final LocalTime END_TRADE = LocalTime.of(14, 30);
    private static final double CAPITAL = 12500.0;
    private static final double BROKERAGE = 0.002;

    private final CandleDataRepository candleRepo;

    public BacktestReport runBacktest(String symbol, LocalDate start, LocalDate end,
                                       double h, double k, int maxHold, double volRatio, double stopLoss) {
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
            if (dayCandles.size() < 100) continue;
            totalDays++;

            var c5 = aggregate5m(dayCandles);
            if (c5.size() < BASELINE_PERIODS + 5) continue;
            int n = c5.size();

            double[] ret = new double[n];
            for (int j = 1; j < n; j++) {
                ret[j] = c5.get(j - 1).close > 0
                    ? Math.log(c5.get(j).close / c5.get(j - 1).close) : 0;
            }

            double[] vol = new double[n];
            for (int j = VOL_PERIODS; j < n; j++) {
                double m = 0, v = 0;
                for (int kk = j - VOL_PERIODS + 1; kk <= j; kk++) m += ret[kk];
                m /= VOL_PERIODS;
                for (int kk = j - VOL_PERIODS + 1; kk <= j; kk++) v += Math.pow(ret[kk] - m, 2);
                vol[j] = Math.sqrt(v / VOL_PERIODS);
            }

            double[] vr = new double[n];
            for (int j = BASELINE_PERIODS; j < n; j++) {
                double[] buf = Arrays.copyOfRange(vol, j - BASELINE_PERIODS, j);
                Arrays.sort(buf);
                double base = buf[BASELINE_PERIODS / 2];
                vr[j] = base > 0 ? vol[j] / base : 0;
            }

            double S_high = 0, S_low = 0;
            boolean inTrade = false;
            boolean longDir = false;
            double entryPx = 0;
            int entryIdx = 0, qty = 0, cd = 0;

            for (int i = BASELINE_PERIODS; i < n; i++) {
                if (vol[i] <= 0) continue;
                LocalTime t = c5.get(i).ts.toLocalTime();
                if (t.isBefore(START_TRADE) || t.isAfter(END_TRADE)) continue;

                double z = ret[i] / vol[i];
                S_high = Math.max(0, S_high + z - k);
                S_low  = Math.max(0, S_low - z - k);

                if (inTrade) {
                    double exitPx = c5.get(i).close;
                    String reason = null;

                    if (longDir && S_low > h) reason = "CUSUM_REV";
                    else if (!longDir && S_high > h) reason = "CUSUM_REV";
                    else if ((i - entryIdx) >= maxHold) reason = "TIME";
                    else {
                        double move = Math.abs(exitPx - entryPx) / entryPx;
                        if (move >= stopLoss) reason = "STOP";
                    }

                    if (reason != null) {
                        double gross = longDir
                            ? (exitPx - entryPx) * qty
                            : (entryPx - exitPx) * qty;
                        double brk = BROKERAGE * entryPx * qty;
                        TradeResult tr = new TradeResult();
                        tr.day = e.getKey();
                        tr.entryTime = c5.get(entryIdx).ts;
                        tr.exitTime = c5.get(i).ts;
                        tr.direction = longDir ? "LONG" : "SHORT";
                        tr.entryPrice = entryPx;
                        tr.exitPrice = exitPx;
                        tr.exitReason = reason;
                        tr.quantity = qty;
                        tr.grossPnL = gross;
                        tr.brokerage = brk;
                        tr.netPnL = gross - brk;
                        trades.add(tr);
                        inTrade = false;
                        S_high = 0; S_low = 0;
                        cd = MIN_GAP;
                    }
                    continue;
                }

                if (cd > 0) { cd--; continue; }
                if (vr[i] < volRatio) continue;

                String reason = null;
                if (S_high > h) { reason = "LONG"; longDir = true; }
                else if (S_low > h) { reason = "SHORT"; longDir = false; }

                if (reason != null) {
                    entryIdx = i;
                    entryPx = c5.get(i).close;
                    qty = Math.max(1, (int) (CAPITAL / entryPx));
                    inTrade = true;
                    S_high = 0; S_low = 0;
                }
            }
        }

        return buildReport(symbol, start, end, totalDays, all.size(), trades);
    }

    record Candle5m(LocalDateTime ts, double open, double high, double low, double close, long volume) {}

    private List<Candle5m> aggregate5m(List<CandleData> oneMin) {
        List<Candle5m> result = new ArrayList<>();
        for (int i = 0; i < oneMin.size(); i += 5) {
            int end = Math.min(i + 5, oneMin.size());
            double o = oneMin.get(i).getOpen().doubleValue();
            double h = 0, l = Double.MAX_VALUE, c = 0;
            long v = 0;
            LocalDateTime ts = oneMin.get(i).getTimestamp();
            for (int j = i; j < end; j++) {
                CandleData x = oneMin.get(j);
                h = Math.max(h, x.getHigh().doubleValue());
                l = Math.min(l, x.getLow().doubleValue());
                c = x.getClose().doubleValue();
                v += x.getVolume() != null ? x.getVolume() : 0;
            }
            result.add(new Candle5m(ts, o, h, l, c, v));
        }
        return result;
    }

    private BacktestReport buildReport(String symbol, LocalDate start, LocalDate end,
                                       int totalDays, int totalCandles, List<TradeResult> trades) {
        var r = new BacktestReport(symbol, start, end, totalDays, null);
        r.totalDays = totalDays;
        r.totalCandles = totalCandles;
        r.totalTrades = trades.size();

        int w = 0, l = 0;
        double g = 0, n = 0, b = 0;
        for (TradeResult t : trades) {
            g += t.grossPnL; n += t.netPnL; b += t.brokerage;
            if (t.netPnL > 0) w++; else if (t.netPnL < 0) l++;
        }

        r.winCount = w; r.lossCount = l;
        r.winRate = trades.size() > 0 ? (double) w / trades.size() : 0;
        r.totalGrossPnL = g; r.totalNetPnL = n; r.totalBrokerage = b;
        r.avgNetPerTrade = trades.size() > 0 ? n / trades.size() : 0;
        r.totalAnomalies = trades.size();
        r.anomaliesPerDay = totalDays > 0 ? (double) trades.size() / totalDays : 0;

        long lng = trades.stream().filter(t -> "LONG".equals(t.direction)).count();
        long shrt = trades.size() - lng;
        long timeX = trades.stream().filter(t -> "TIME".equals(t.exitReason)).count();
        long revX = trades.stream().filter(t -> "CUSUM_REV".equals(t.exitReason)).count();
        long stopX = trades.stream().filter(t -> "STOP".equals(t.exitReason)).count();

        var s = new BacktestReport.TypeRow();
        s.type = "SUMMARY"; s.count = trades.size(); s.winRate = r.winRate;
        s.grossPnL = g; s.netPnL = n; s.brokerage = b;
        r.typeResults.add(s);

        var d = new BacktestReport.TypeRow();
        d.type = "LONG"; d.count = (int) lng; r.typeResults.add(d);
        d = new BacktestReport.TypeRow(); d.type = "SHORT"; d.count = (int) shrt; r.typeResults.add(d);
        d = new BacktestReport.TypeRow(); d.type = "EXIT_TIME"; d.count = (int) timeX; r.typeResults.add(d);
        d = new BacktestReport.TypeRow(); d.type = "EXIT_REV"; d.count = (int) revX; r.typeResults.add(d);
        d = new BacktestReport.TypeRow(); d.type = "EXIT_STOP"; d.count = (int) stopX; r.typeResults.add(d);

        return r;
    }

    private List<CandleData> loadCandles(String symbol, LocalDate start, LocalDate end) {
        var from = start.atTime(9, 15);
        var to = end.atTime(15, 30);
        return candleRepo.findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
            symbol, "1min", from, to);
    }

    static class TradeResult {
        LocalDate day;
        LocalDateTime entryTime, exitTime;
        String direction, exitReason;
        double entryPrice, exitPrice;
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
