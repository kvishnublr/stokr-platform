package com.stokr.backtest;

import com.stokr.engine.CandleData;
import com.stokr.engine.CandleDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Backtests sudden-move anomaly detection at candle level using real 1-min data.
 *
 * Detection (all at candle level, no tick simulation):
 *   VOLUME_SURGE    — current vol > mean + 3σ of last 20 candles
 *   PRICE_SPIKE     — |return| > mean + 2.5σ of last 20 returns
 *   VWAP_DEVIATION  — close deviates > 0.5% from cumulative VWAP
 *   NARROW_BREAKOUT — last 5 candles avg range < 0.5% AND current > 3x that
 *
 * Trade simulation: enter at candle close, exit after 2 candles,
 * ₹12,500/trade, 0.2% round-trip brokerage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuddenMoveBacktestService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final double CAPITAL_PER_TRADE = 12500.0;
    private static final double BROKERAGE_RATE = 0.002;

    private final CandleDataRepository candleRepo;

    public BacktestReport runBacktest(String symbol, LocalDate startDate, LocalDate endDate) {
        List<CandleData> allCandles = loadCandles(symbol, startDate, endDate);
        if (allCandles.size() < 30) {
            return new BacktestReport(symbol, startDate, endDate, 0, "Insufficient data");
        }

        Map<LocalDate, List<CandleData>> byDay = allCandles.stream()
            .collect(Collectors.groupingBy(c -> c.getTimestamp().toLocalDate(), TreeMap::new, Collectors.toList()));

        Map<String, List<TradeResult>> tradesByType = new LinkedHashMap<>();
        int totalDays = 0;
        int totalCandles = allCandles.size();

        for (Map.Entry<LocalDate, List<CandleData>> day : byDay.entrySet()) {
            List<CandleData> dayCandles = day.getValue();
            if (dayCandles.size() < 30) continue;
            totalDays++;

            int n = dayCandles.size();
            double[] rangePcts = new double[n];
            double[] returns = new double[n];
            double[] volumes = new double[n];
            double[] vwaps = new double[n];

            BigDecimal cumTpV = BigDecimal.ZERO, cumV = BigDecimal.ZERO;
            for (int j = 0; j < n; j++) {
                CandleData c = dayCandles.get(j);
                double high = c.getHigh().doubleValue();
                double low = c.getLow().doubleValue();
                double close = c.getClose().doubleValue();
                long vol = c.getVolume() != null ? c.getVolume() : 0;
                rangePcts[j] = (high - low) / close;
                returns[j] = j > 0 ? (close - dayCandles.get(j - 1).getClose().doubleValue()) / dayCandles.get(j - 1).getClose().doubleValue() : 0;
                volumes[j] = vol;
                BigDecimal tp = BigDecimal.valueOf(high).add(BigDecimal.valueOf(low)).add(BigDecimal.valueOf(close))
                    .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
                cumTpV = cumTpV.add(tp.multiply(BigDecimal.valueOf(vol)));
                cumV = cumV.add(BigDecimal.valueOf(vol));
                vwaps[j] = cumV.compareTo(BigDecimal.ZERO) > 0
                    ? cumTpV.divide(cumV, 2, RoundingMode.HALF_UP).doubleValue() : 0;
            }

            // Rolling windows
            LinkedList<Double> volWindow = new LinkedList<>();
            LinkedList<Double> retWindow = new LinkedList<>();

            for (int i = 0; i < n; i++) {
                volWindow.addLast(volumes[i]);
                if (volWindow.size() > 20) volWindow.removeFirst();
                retWindow.addLast(returns[i]);
                if (retWindow.size() > 20) retWindow.removeFirst();

                if (i < 20) continue;

                CandleData candle = dayCandles.get(i);
                double close = candle.getClose().doubleValue();
                double high = candle.getHigh().doubleValue();
                double low = candle.getLow().doubleValue();
                boolean isGreen = close >= candle.getOpen().doubleValue();

                // Detect signals
                List<String> signals = new ArrayList<>();

                // VOLUME_SURGE: vol > mean + 3σ
                double vMean = volWindow.stream().mapToDouble(d -> d).average().orElse(0);
                double vStd = Math.sqrt(volWindow.stream().mapToDouble(d -> Math.pow(d - vMean, 2)).average().orElse(0));
                if (vStd > 0 && volumes[i] > vMean + 3 * vStd) {
                    signals.add("VOLUME_SURGE");
                }

                // PRICE_SPIKE: |return| > mean + 2.5σ
                double rMean = retWindow.stream().mapToDouble(d -> d).average().orElse(0);
                double rStd = Math.sqrt(retWindow.stream().mapToDouble(d -> Math.pow(d - rMean, 2)).average().orElse(0));
                if (rStd > 0 && Math.abs(returns[i]) > rStd * 2.5) {
                    signals.add("PRICE_SPIKE");
                }

                // VWAP_DEVIATION: close > vwap + 0.5% or < vwap - 0.5%
                if (vwaps[i] > 0) {
                    double devPct = (close - vwaps[i]) / vwaps[i];
                    if (Math.abs(devPct) > 0.005) {
                        signals.add("VWAP_DEVIATION");
                    }
                }

                // NARROW_BREAKOUT: last 5 candles avg range < 0.5% AND current > 3x that
                if (i >= 5) {
                    double avgPrevRange = 0;
                    for (int k = i - 5; k < i; k++) avgPrevRange += rangePcts[k];
                    avgPrevRange /= 5;
                    if (avgPrevRange < 0.005 && rangePcts[i] > avgPrevRange * 3) {
                        signals.add("NARROW_BREAKOUT");
                    }
                }

                // Execute trades for each signal
                CandleData nextNextCandle = i + 2 < n ? dayCandles.get(i + 2) : null;

                for (String type : signals) {
                    TradeResult tr = new TradeResult();
                    tr.type = type;
                    tr.day = day.getKey();
                    tr.time = candle.getTimestamp();
                    tr.entryPrice = close;
                    tr.direction = isGreen ? "UP" : "DOWN";
                    tr.isLong = (type.equals("VWAP_DEVIATION") && close > vwaps[i]) || (!type.equals("VWAP_DEVIATION") && isGreen);
                    tr.quantity = Math.max(1, (int)(CAPITAL_PER_TRADE / close));

                    if (nextNextCandle != null) {
                        tr.exitPrice = nextNextCandle.getClose().doubleValue();
                        double gross = tr.isLong
                            ? (tr.exitPrice - tr.entryPrice) * tr.quantity
                            : (tr.entryPrice - tr.exitPrice) * tr.quantity;
                        tr.grossPnL = gross;
                        tr.brokerage = BROKERAGE_RATE * tr.entryPrice * tr.quantity;
                        tr.netPnL = gross - tr.brokerage;
                    } else {
                        tr.exitPrice = tr.entryPrice;
                        tr.grossPnL = 0;
                        tr.brokerage = BROKERAGE_RATE * tr.entryPrice * tr.quantity;
                        tr.netPnL = -tr.brokerage;
                    }

                    tradesByType.computeIfAbsent(type, k -> new ArrayList<>()).add(tr);
                }
            }
        }

        return buildReport(symbol, startDate, endDate, totalDays, totalCandles, tradesByType);
    }

    private BacktestReport buildReport(String symbol, LocalDate start, LocalDate end,
                                        int totalDays, int totalCandles,
                                        Map<String, List<TradeResult>> tradesByType) {
        var report = new BacktestReport(symbol, start, end, totalDays, null);
        report.totalDays = totalDays;
        report.totalCandles = totalCandles;

        int totalTrades = 0;
        double totalGross = 0, totalNet = 0, totalBrokerage = 0;
        int winCount = 0, lossCount = 0;

        for (Map.Entry<String, List<TradeResult>> entry : tradesByType.entrySet()) {
            String type = entry.getKey();
            List<TradeResult> trades = entry.getValue();
            totalTrades += trades.size();

            double avgReturn = trades.stream().filter(t -> t.exitPrice != t.entryPrice)
                .mapToDouble(t -> (t.exitPrice - t.entryPrice) / t.entryPrice * (t.isLong ? 1 : -1))
                .average().orElse(0);
            double winRate = trades.stream().filter(t -> t.netPnL > 0).count() / (double) Math.max(1, trades.size());
            double typeGross = trades.stream().mapToDouble(t -> t.grossPnL).sum();
            double typeNet = trades.stream().mapToDouble(t -> t.netPnL).sum();
            double typeBrokerage = trades.stream().mapToDouble(t -> t.brokerage).sum();
            double best = trades.stream().mapToDouble(t -> t.netPnL).max().orElse(0);
            double worst = trades.stream().mapToDouble(t -> t.netPnL).min().orElse(0);

            var row = new BacktestReport.TypeRow();
            row.type = type;
            row.count = trades.size();
            row.winRate = winRate;
            row.avgReturn = avgReturn;
            row.grossPnL = typeGross;
            row.netPnL = typeNet;
            row.brokerage = typeBrokerage;
            row.bestTrade = best;
            row.worstTrade = worst;
            report.typeResults.add(row);

            for (TradeResult t : trades) {
                totalGross += t.grossPnL;
                totalNet += t.netPnL;
                totalBrokerage += t.brokerage;
                if (t.netPnL > 0) winCount++;
                else if (t.netPnL < 0) lossCount++;
            }
        }

        report.totalTrades = totalTrades;
        report.winCount = winCount;
        report.lossCount = lossCount;
        report.winRate = totalTrades > 0 ? (double) winCount / totalTrades : 0;
        report.totalGrossPnL = totalGross;
        report.totalBrokerage = totalBrokerage;
        report.totalNetPnL = totalNet;
        report.avgNetPerTrade = totalTrades > 0 ? totalNet / totalTrades : 0;
        report.totalAnomalies = totalTrades;
        report.anomaliesPerDay = totalDays > 0 ? (double) totalTrades / totalDays : 0;
        return report;
    }

    private List<CandleData> loadCandles(String symbol, LocalDate start, LocalDate end) {
        LocalDateTime from = start.atTime(9, 15);
        LocalDateTime to = end.atTime(15, 30);
        return candleRepo.findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
            symbol, "1min", from, to);
    }

    // ─── DTOs ───────────────────────────────────────────────────────────

    static class TradeResult {
        String type;
        LocalDate day;
        LocalDateTime time;
        double entryPrice;
        double exitPrice;
        String direction;
        boolean isLong;
        int quantity;
        double grossPnL;
        double brokerage;
        double netPnL;
    }

    public static class BacktestReport {
        public String symbol;
        public LocalDate startDate, endDate;
        public int totalDays, totalCandles, totalAnomalies;
        public double anomaliesPerDay;
        public int totalTrades;
        public int winCount, lossCount;
        public double winRate;
        public double totalGrossPnL;
        public double totalBrokerage;
        public double totalNetPnL;
        public double avgNetPerTrade;
        public final List<TypeRow> typeResults = new ArrayList<>();
        public String error;

        BacktestReport(String s, LocalDate sd, LocalDate ed, int td, String err) {
            symbol = s; startDate = sd; endDate = ed; totalDays = td; error = err;
        }

        public static class TypeRow {
            public String type;
            public int count;
            public double winRate;
            public double avgReturn;
            public double grossPnL;
            public double netPnL;
            public double brokerage;
            public double bestTrade;
            public double worstTrade;
        }
    }
}
