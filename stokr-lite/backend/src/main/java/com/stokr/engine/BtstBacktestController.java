package com.stokr.engine;

import com.stokr.marketdata.Candle;
import com.stokr.marketdata.ZerodhaLiveDataScheduler;
import com.stokr.strategy.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BTST-specific backtest with exit timing analysis.
 *
 * Runs the BtstStrategy.evaluate() on historical 1-min candle data,
 * simulates next-morning exits, and reports:
 * - Win rate, P&L, expectancy, max drawdown, profit factor
 * - Exit timing breakdown (9:15-9:20, 9:20-9:30, 9:30-9:45, after trail)
 * - Exit type breakdown (TARGET_HIT, SL_HIT, TRAIL_HIT, GAP_EXIT)
 * - Capital deployed, total invested, net returns
 */
@Slf4j
@RestController
@RequestMapping("/api/backtest/btst")
@RequiredArgsConstructor
public class BtstBacktestController {

    private final CandleDataRepository candleRepo;
    private final List<StrategyPlugin> plugins;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final double CAPITAL_PER_TRADE = 15000.0;
    private static final int MAX_POSITIONS = 5;

    /**
     * Run BTST backtest over a date range.
     *
     * Query params:
     *   months=1|3|6  — lookback period
     *   capital=15000 — capital per trade
     *   brokerage=40  — round-trip brokerage
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runBacktest(
            @RequestParam(defaultValue = "3") int months,
            @RequestParam(defaultValue = "15000") double capital,
            @RequestParam(defaultValue = "40") double brokerage) {

        if (months < 1 || months > 12) {
            return ResponseEntity.badRequest().body(Map.of("error", "months must be 1-12"));
        }

        LocalDateTime endTime = LocalDateTime.now(IST);
        LocalDateTime startTime = endTime.minusMonths(months);

        log.info("BTST backtest: {} months, {} to {}, capital={}", months, startTime, endTime, capital);

        StrategyPlugin plugin = plugins.stream()
            .filter(p -> "BTST".equals(p.getStrategyType()))
            .findFirst().orElse(null);

        if (plugin == null) {
            return ResponseEntity.status(500).body(Map.of("error", "BTST strategy plugin not found"));
        }

        StrategyParams params = StrategyParams.defaults();

        // Get all symbols that have candle data
        List<String> symbols = candleRepo.findAllSymbols();
        if (symbols.isEmpty()) {
            symbols = ZerodhaLiveDataScheduler.NIFTY_500;
        }

        // Trading days in period
        List<LocalDate> tradingDays = getTradingDays(startTime.toLocalDate(), endTime.toLocalDate());
        log.info("{} trading days in period, {} symbols", tradingDays.size(), symbols.size());

        List<BtstTrade> allTrades = new ArrayList<>();
        Map<String, Double> symbolReturns = new LinkedHashMap<>(); // symbol -> total PnL

        for (String symbol : symbols) {
            // Load 1-min candles for the period
            List<CandleData> raw = candleRepo.findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
                symbol, "1min", startTime, endTime);

            if (raw.size() < 300) continue; // need at least 1 day of data

            // Group candles by date → list of daily series
            Map<LocalDate, List<CandleData>> byDate = new LinkedHashMap<>();
            for (CandleData cd : raw) {
                LocalDate d = cd.getTimestamp().toLocalDate();
                byDate.computeIfAbsent(d, k -> new ArrayList<>()).add(cd);
            }

            for (LocalDate date : tradingDays) {
                List<CandleData> dayCandles = byDate.get(date);
                if (dayCandles == null || dayCandles.size() < 300) continue;

                // Build MarketContext for EOD evaluation
                List<Candle> candleList = dayCandles.stream().map(this::toCandle).collect(Collectors.toList());

                // Find EOD candle (~3:20 PM)
                Candle eodCandle = null;
                int eodIdx = -1;
                for (int i = candleList.size() - 1; i >= 0; i--) {
                    LocalTime t = candleList.get(i).timestamp().toLocalTime();
                    if (!t.isBefore(LocalTime.of(15, 10)) && !t.isAfter(LocalTime.of(15, 25))) {
                        eodCandle = candleList.get(i);
                        eodIdx = i;
                        break;
                    }
                }
                if (eodCandle == null) continue;

                // Compute VWAP for the day
                BigDecimal vwap = computeVwap(dayCandles);

                Map<String, BigDecimal> indicators = new HashMap<>();
                Map<String, Object> extras = new HashMap<>();

                MarketContext ctx = new MarketContext(
                    symbol, candleList, eodCandle.close(), vwap, indicators, extras);

                // Evaluate strategy at EOD
                Signal sig = plugin.evaluate(ctx, params);
                if (sig == null || !sig.isValid()) continue;
                if (sig.side() != Signal.Side.BUY) continue;

                // Simulate next-day exit
                LocalDate nextDay = getNextTradingDay(date, tradingDays);
                List<CandleData> nextDayCandles = byDate.get(nextDay);
                if (nextDayCandles == null || nextDayCandles.isEmpty()) continue;

                BtstTrade trade = simulateBtstExit(
                    symbol, sig, candleList, eodIdx, nextDayCandles, date, nextDay, capital, brokerage);

                if (trade != null) {
                    allTrades.add(trade);
                    symbolReturns.merge(symbol, trade.netPnl, Double::sum);
                }
            }
        }

        // Sort by entry date
        allTrades.sort(Comparator.comparing(t -> t.entryDate));

        // Aggregate results
        BtstReport report = aggregateResults(allTrades, capital, months, startTime, endTime);

        return ResponseEntity.ok(report.toMap());
    }

    /**
     * Theoretical model — computes expected returns based on strategy parameters
     * without needing actual candle data. Useful for sizing and planning.
     */
    @GetMapping("/model")
    public ResponseEntity<Map<String, Object>> model(
            @RequestParam(defaultValue = "15000") double capital,
            @RequestParam(defaultValue = "40") double brokerage,
            @RequestParam(defaultValue = "62") double winRatePct,
            @RequestParam(defaultValue = "1.5") double avgWinPct,
            @RequestParam(defaultValue = "1.1") double avgLossPct) {

        winRatePct = Math.min(100, Math.max(0, winRatePct));
        double lossRatePct = 100 - winRatePct;
        double winRate = winRatePct / 100.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "BTST Theoretical Model");
        result.put("assumptions", Map.of(
            "capitalPerTrade", capital,
            "brokeragePerTrade", brokerage,
            "winRate", winRatePct + "%",
            "avgWin", avgWinPct + "%",
            "avgLoss", avgLossPct + "%",
            "signalsPerDay", "3-5 (across 500 stocks)",
            "maxPositions", MAX_POSITIONS
        ));

        // Per-trade expectancy
        double avgWinAmount = capital * (avgWinPct / 100.0);
        double avgLossAmount = capital * (avgLossPct / 100.0);
        double expectancy = (winRate * avgWinAmount) - ((1 - winRate) * avgLossAmount);
        double expectancyPct = expectancy / capital * 100;

        result.put("perTradeExpectancy", r2(expectancy));
        result.put("perTradeExpectancyPct", r2(expectancyPct) + "%");

        // Monthly projections
        int[] monthPeriods = {1, 3, 6};
        for (int m : monthPeriods) {
            int tradingDays = m * 20;
            int estimatedSignals = (int)(tradingDays * 4.0); // ~4 signals/day
            int estimatedTrades = Math.min(estimatedSignals, tradingDays * MAX_POSITIONS / 2); // position limit

            double totalPnL = estimatedTrades * expectancy;
            double totalBrokerage = estimatedTrades * brokerage;
            double netPnL = totalPnL - totalBrokerage;
            double totalCapitalDeployed = capital * MAX_POSITIONS; // max concurrent
            double roi = (netPnL / totalCapitalDeployed) * 100;

            Map<String, Object> period = new LinkedHashMap<>();
            period.put("tradingDays", tradingDays);
            period.put("estimatedTrades", estimatedTrades);
            period.put("grossPnL", r2(totalPnL));
            period.put("brokerage", r2(totalBrokerage));
            period.put("netPnL", r2(netPnL));
            period.put("capitalRequired", r2(totalCapitalDeployed));
            period.put("roi", r2(roi) + "%");

            // Monthly breakdown
            double monthlyNet = netPnL / m;
            period.put("perMonth", r2(monthlyNet));
            period.put("perMonthRoi", r2(monthlyNet / totalCapitalDeployed * 100) + "%");

            result.put(m + "month", period);
        }

        // Risk metrics
        double maxConsecutiveLosses = 8; // theoretical max losing streak at 38% loss rate
        double maxDrawdownAmount = maxConsecutiveLosses * avgLossAmount;
        result.put("riskMetrics", Map.of(
            "maxConsecutiveLosses", (int)maxConsecutiveLosses,
            "maxDrawdownAmount", r2(maxDrawdownAmount),
            "maxDrawdownPct", r2(maxDrawdownAmount / (capital * MAX_POSITIONS) * 100) + "%",
            "breakevenWinRate", r2(brokerage / (capital * (avgWinPct/100)) * 100) + "%"
        ));

        return ResponseEntity.ok(result);
    }

    // ──── Backtest simulation ────

    private BtstTrade simulateBtstExit(String symbol, Signal sig,
                                        List<Candle> entryDayCandles, int entryIdx,
                                        List<CandleData> nextDayCandles,
                                        LocalDate entryDate, LocalDate exitDate,
                                        double capital, double brokerage) {

        double entryPx = sig.entryPrice().doubleValue();
        double sl = sig.stopLoss().doubleValue();
        double target = sig.target().doubleValue();
        double trailTrigger = sig.trailTriggerPct();
        double trailDistance = sig.trailDistancePct();

        int qty = (int)(capital / entryPx);
        if (qty <= 0) return null;
        double deployed = qty * entryPx;

        // Walk through next day's 1-min candles
        double bestPrice = 0;
        double trailingSl = sl;
        boolean trailActive = false;

        for (int i = 0; i < nextDayCandles.size(); i++) {
            CandleData cd = nextDayCandles.get(i);
            LocalTime t = cd.getTimestamp().toLocalTime();

            // Skip pre-market
            if (t.isBefore(LocalTime.of(9, 15))) continue;
            // Force exit by 10:00 AM (time stop)
            if (t.isAfter(LocalTime.of(10, 0))) {
                double exitPx = cd.getClose().doubleValue();
                double move = (exitPx - entryPx) / entryPx * 100;
                double pnl = move / 100 * deployed;
                return new BtstTrade(symbol, entryDate, exitDate, entryPx, exitPx,
                    move, pnl, brokerage, deployed, qty, "TIME_STOP", t);
            }

            double high = cd.getHigh().doubleValue();
            double low = cd.getLow().doubleValue();
            double close = cd.getClose().doubleValue();

            // Gap-down at open: exit immediately
            if (i == 0 && close < sl) {
                double move = (close - entryPx) / entryPx * 100;
                double pnl = move / 100 * deployed;
                return new BtstTrade(symbol, entryDate, exitDate, entryPx, close,
                    move, pnl, brokerage, deployed, qty, "GAP_EXIT", t);
            }

            // Trail stop
            if (i > 0) {
                double gainPct = (high - entryPx) / entryPx * 100;
                if (gainPct >= trailTrigger) {
                    trailActive = true;
                    if (high > bestPrice) bestPrice = high;
                    trailingSl = bestPrice * (1.0 - trailDistance / 100.0);
                }
            }

            // Check exits
            if (high >= target) {
                double exitPx = target;
                double move = (exitPx - entryPx) / entryPx * 100;
                double pnl = move / 100 * deployed;
                return new BtstTrade(symbol, entryDate, exitDate, entryPx, exitPx,
                    move, pnl, brokerage, deployed, qty, "TARGET_HIT", t);
            }

            if (low <= trailingSl) {
                double exitPx = trailingSl;
                if (trailActive) {
                    double move = (exitPx - entryPx) / entryPx * 100;
                    double pnl = move / 100 * deployed;
                    return new BtstTrade(symbol, entryDate, exitDate, entryPx, exitPx,
                        move, pnl, brokerage, deployed, qty, "TRAIL_HIT", t);
                } else {
                    double move = (exitPx - entryPx) / entryPx * 100;
                    double pnl = move / 100 * deployed;
                    return new BtstTrade(symbol, entryDate, exitDate, entryPx, exitPx,
                        move, pnl, brokerage, deployed, qty, "SL_HIT", t);
                }
            }

            // Last candle of the day: exit at close
            if (i == nextDayCandles.size() - 1) {
                double exitPx = close;
                double move = (exitPx - entryPx) / entryPx * 100;
                double pnl = move / 100 * deployed;
                return new BtstTrade(symbol, entryDate, exitDate, entryPx, exitPx,
                    move, pnl, brokerage, deployed, qty, "EOD_CLOSE", t);
            }
        }

        return null; // shouldn't reach here
    }

    // ──── Aggregation ────

    private BtstReport aggregateResults(List<BtstTrade> trades, double capital,
                                         int months, LocalDateTime start, LocalDateTime end) {
        BtstReport r = new BtstReport();
        r.period = months + " months";
        r.dateRange = start.toLocalDate() + " to " + end.toLocalDate();
        r.totalTrades = trades.size();
        r.totalCapitalPerTrade = capital;
        r.maxPositions = MAX_POSITIONS;
        r.totalCapitalRequired = capital * MAX_POSITIONS;

        if (trades.isEmpty()) {
            r.note = "No trades found. Run historical backfill first (POST /api/admin/backfill/historical?months=" + months + ")";
            return r;
        }

        // Win/loss
        long wins = trades.stream().filter(t -> t.netPnl > 0).count();
        long losses = trades.stream().filter(t -> t.netPnl <= 0).count();
        r.winCount = wins;
        r.lossCount = losses;
        r.winRate = trades.isEmpty() ? 0 : (double) wins / trades.size() * 100;

        // P&L
        double totalGross = trades.stream().mapToDouble(t -> t.grossPnl).sum();
        double totalBrokerage = trades.stream().mapToDouble(t -> t.brokerage).sum();
        double totalNet = totalGross - totalBrokerage;
        r.totalGrossPnl = totalGross;
        r.totalBrokerage = totalBrokerage;
        r.totalNetPnl = totalNet;
        r.totalInvested = trades.stream().mapToDouble(t -> t.deployedCapital).sum();
        r.roi = r.totalCapitalRequired > 0 ? totalNet / r.totalCapitalRequired * 100 : 0;

        // Per-trade stats
        DoubleSummaryStatistics winStats = trades.stream()
            .filter(t -> t.netPnl > 0).mapToDouble(t -> t.netPnl).summaryStatistics();
        DoubleSummaryStatistics lossStats = trades.stream()
            .filter(t -> t.netPnl <= 0).mapToDouble(t -> t.netPnl).summaryStatistics();

        r.avgWin = winStats.getCount() > 0 ? winStats.getAverage() : 0;
        r.avgLoss = lossStats.getCount() > 0 ? lossStats.getAverage() : 0;
        r.avgWinPct = trades.stream().filter(t -> t.netPnl > 0)
            .mapToDouble(t -> t.movePct).average().orElse(0);
        r.avgLossPct = trades.stream().filter(t -> t.netPnl <= 0)
            .mapToDouble(t -> t.movePct).average().orElse(0);
        r.expectancyPerTrade = totalNet / trades.size();
        r.largestWin = winStats.getMax();
        r.largestLoss = lossStats.getMin();
        r.profitFactor = totalBrokerage + (losses > 0 ? Math.abs(lossStats.getSum()) : 0) > 0
            ? Math.abs(winStats.getSum()) / (Math.abs(lossStats.getSum()) + totalBrokerage) : 999;

        // Max drawdown
        r.maxDrawdown = computeMaxDrawdown(trades);

        // Exit timing breakdown
        Map<String, Long> exitTypeCount = trades.stream()
            .collect(Collectors.groupingBy(t -> t.exitType, Collectors.counting()));
        r.exitTypes = exitTypeCount;

        // Time-of-day exit breakdown
        Map<String, Long> exitTimeBreakdown = new LinkedHashMap<>();
        for (BtstTrade t : trades) {
            String bucket;
            if (t.exitTime == null) bucket = "UNKNOWN";
            else if (!t.exitTime.isAfter(LocalTime.of(9, 20))) bucket = "9:15-9:20";
            else if (!t.exitTime.isAfter(LocalTime.of(9, 30))) bucket = "9:20-9:30";
            else if (!t.exitTime.isAfter(LocalTime.of(9, 45))) bucket = "9:30-9:45";
            else bucket = "9:45-10:00";
            exitTimeBreakdown.merge(bucket, 1L, Long::sum);
        }
        r.exitTimeBreakdown = exitTimeBreakdown;

        // P&L by exit type
        Map<String, Double> pnlByExitType = new LinkedHashMap<>();
        for (BtstTrade t : trades) {
            pnlByExitType.merge(t.exitType, t.netPnl, Double::sum);
        }
        r.pnlByExitType = pnlByExitType;

        // Win rate by exit time
        Map<String, Double> winRateByTime = new LinkedHashMap<>();
        for (String bucket : exitTimeBreakdown.keySet()) {
            long totalInBucket = trades.stream()
                .filter(t -> timeBucket(t.exitTime).equals(bucket)).count();
            long winsInBucket = trades.stream()
                .filter(t -> timeBucket(t.exitTime).equals(bucket) && t.netPnl > 0).count();
            winRateByTime.put(bucket, totalInBucket > 0 ? (double) winsInBucket / totalInBucket * 100 : 0);
        }
        r.winRateByExitTime = winRateByTime;

        // Monthly breakdown
        Map<String, double[]> monthlyData = new LinkedHashMap<>();
        for (BtstTrade t : trades) {
            String month = t.entryDate.getYear() + "-" + String.format("%02d", t.entryDate.getMonthValue());
            double[] data = monthlyData.computeIfAbsent(month, k -> new double[4]); // [trades, wins, gross, net]
            data[0]++;
            if (t.netPnl > 0) data[1]++;
            data[2] += t.grossPnl;
            data[3] += t.netPnl;
        }
        List<Map<String, Object>> monthlyBreakdown = new ArrayList<>();
        for (var entry : new TreeMap<>(monthlyData).entrySet()) {
            double[] d = entry.getValue();
            monthlyBreakdown.add(Map.of(
                "month", entry.getKey(),
                "trades", (int)d[0],
                "wins", (int)d[1],
                "winRate", r2(d[1]/d[0]*100) + "%",
                "grossPnl", r2(d[2]),
                "netPnl", r2(d[3])
            ));
        }
        r.monthlyBreakdown = monthlyBreakdown;

        // Top/bottom performers
        r.topSymbols = trades.stream()
            .collect(Collectors.groupingBy(t -> t.symbol,
                Collectors.summingDouble(t -> t.netPnl)))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(10)
            .map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("symbol", e.getKey());
                m.put("netPnl", r2(e.getValue()));
                return m;
            })
            .collect(Collectors.toList());

        r.worstSymbols = trades.stream()
            .collect(Collectors.groupingBy(t -> t.symbol,
                Collectors.summingDouble(t -> t.netPnl)))
            .entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .limit(10)
            .map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("symbol", e.getKey());
                m.put("netPnl", r2(e.getValue()));
                return m;
            })
            .collect(Collectors.toList());

        return r;
    }

    private double computeMaxDrawdown(List<BtstTrade> trades) {
        double peak = 0, maxDd = 0, running = 0;
        for (BtstTrade t : trades) {
            running += t.netPnl;
            if (running > peak) peak = running;
            double dd = peak - running;
            if (dd > maxDd) maxDd = dd;
        }
        return maxDd;
    }

    // ──── Helpers ────

    private String timeBucket(LocalTime t) {
        if (t == null) return "UNKNOWN";
        if (!t.isAfter(LocalTime.of(9, 20))) return "9:15-9:20";
        if (!t.isAfter(LocalTime.of(9, 30))) return "9:20-9:30";
        if (!t.isAfter(LocalTime.of(9, 45))) return "9:30-9:45";
        return "9:45-10:00";
    }

    private BigDecimal computeVwap(List<CandleData> candles) {
        BigDecimal sumPV = BigDecimal.ZERO;
        long totalVol = 0;
        for (CandleData c : candles) {
            BigDecimal tp = c.getHigh().add(c.getLow()).add(c.getClose())
                .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            sumPV = sumPV.add(tp.multiply(BigDecimal.valueOf(c.getVolume())));
            totalVol += c.getVolume();
        }
        return totalVol > 0 ? sumPV.divide(BigDecimal.valueOf(totalVol), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    private Candle toCandle(CandleData cd) {
        return new Candle(cd.getSymbol(), cd.getTimestamp(),
            cd.getOpen(), cd.getHigh(), cd.getLow(), cd.getClose(), cd.getVolume());
    }

    private List<LocalDate> getTradingDays(LocalDate start, LocalDate end) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate d = start;
        while (!d.isAfter(end)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                days.add(d);
            }
            d = d.plusDays(1);
        }
        return days;
    }

    private LocalDate getNextTradingDay(LocalDate date, List<LocalDate> tradingDays) {
        int idx = tradingDays.indexOf(date);
        return idx >= 0 && idx + 1 < tradingDays.size() ? tradingDays.get(idx + 1) : date.plusDays(1);
    }

    private static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ──── Data classes ────

    static class BtstTrade {
        String symbol;
        LocalDate entryDate, exitDate;
        double entryPrice, exitPrice, movePct, grossPnl, brokerage, deployedCapital;
        int quantity;
        String exitType;
        LocalTime exitTime;
        double netPnl;

        BtstTrade(String symbol, LocalDate entryDate, LocalDate exitDate,
                  double entryPrice, double exitPrice, double movePct,
                  double grossPnl, double brokerage, double deployed, int qty,
                  String exitType, LocalTime exitTime) {
            this.symbol = symbol;
            this.entryDate = entryDate;
            this.exitDate = exitDate;
            this.entryPrice = entryPrice;
            this.exitPrice = exitPrice;
            this.movePct = movePct;
            this.grossPnl = grossPnl;
            this.brokerage = brokerage;
            this.deployedCapital = deployed;
            this.quantity = qty;
            this.exitType = exitType;
            this.exitTime = exitTime;
            this.netPnl = grossPnl - brokerage;
        }
    }

    static class BtstReport {
        String period, dateRange, note;
        int totalTrades, maxPositions;
        long winCount, lossCount;
        double winRate, totalGrossPnl, totalBrokerage, totalNetPnl;
        double totalCapitalPerTrade, totalCapitalRequired, totalInvested, roi;
        double avgWin, avgLoss, avgWinPct, avgLossPct;
        double expectancyPerTrade, largestWin, largestLoss, profitFactor, maxDrawdown;
        Map<String, Long> exitTypes;
        Map<String, Long> exitTimeBreakdown;
        Map<String, Double> pnlByExitType;
        Map<String, Double> winRateByExitTime;
        List<Map<String, Object>> monthlyBreakdown;
        List<Map<String, Object>> topSymbols, worstSymbols;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", period);
            m.put("dateRange", dateRange);
            m.put("capitalPerTrade", totalCapitalPerTrade);
            m.put("maxConcurrentPositions", maxPositions);
            m.put("totalCapitalRequired", r2(totalCapitalRequired));
            m.put("totalInvested", r2(totalInvested));
            m.put("totalTrades", totalTrades);
            m.put("winCount", winCount);
            m.put("lossCount", lossCount);
            m.put("winRate", r2(winRate) + "%");
            m.put("avgWinPct", r2(avgWinPct) + "%");
            m.put("avgLossPct", r2(avgLossPct) + "%");
            m.put("avgWinAmount", r2(avgWin));
            m.put("avgLossAmount", r2(avgLoss));
            m.put("expectancyPerTrade", r2(expectancyPerTrade));
            m.put("totalGrossPnl", r2(totalGrossPnl));
            m.put("totalBrokerage", r2(totalBrokerage));
            m.put("totalNetPnl", r2(totalNetPnl));
            m.put("roi", r2(roi) + "%");
            m.put("profitFactor", r2(profitFactor));
            m.put("maxDrawdown", r2(maxDrawdown));
            m.put("largestWin", r2(largestWin));
            m.put("largestLoss", r2(largestLoss));
            m.put("exitTypeBreakdown", exitTypes);
            m.put("exitTimeBreakdown", exitTimeBreakdown);
            m.put("pnlByExitType", pnlByExitType);
            m.put("winRateByExitTime", winRateByExitTime);
            m.put("monthlyBreakdown", monthlyBreakdown);
            m.put("topSymbols", topSymbols);
            m.put("worstSymbols", worstSymbols);
            if (note != null) m.put("note", note);
            return m;
        }
    }
}
