package com.stokr.engine;

import com.stokr.marketdata.Candle;
import com.stokr.strategy.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * QuickFlip backtest — tests all 4 intraday patterns against historical 1-min candles.
 */
@Slf4j
@RestController
@RequestMapping("/api/backtest/quickflip")
@RequiredArgsConstructor
public class QuickFlipBacktestController {

    private final CandleDataRepository candleRepo;
    private final List<StrategyPlugin> plugins;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final double CAPITAL_PER_TRADE = 12000.0;
    private static final double BROKERAGE = 40.0;

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runBacktest(
            @RequestParam(defaultValue = "3") int months,
            @RequestParam(defaultValue = "12000") double capital,
            @RequestParam(defaultValue = "40") double brokerage) {

        if (months < 1 || months > 12)
            return ResponseEntity.badRequest().body(Map.of("error", "months 1-12"));

        LocalDateTime endTime = LocalDateTime.now(IST);
        LocalDateTime startTime = endTime.minusMonths(months);

        log.info("QuickFlip backtest: {} months", months);

        StrategyPlugin plugin = plugins.stream()
            .filter(p -> "QUICK_FLIP".equals(p.getStrategyType()))
            .findFirst().orElse(null);
        if (plugin == null)
            return ResponseEntity.status(500).body(Map.of("error", "QUICK_FLIP plugin not found"));

        StrategyParams params = StrategyParams.defaults();
        List<String> symbols = candleRepo.findAllSymbols();
        if (symbols.isEmpty()) symbols = com.stokr.marketdata.ZerodhaLiveDataScheduler.NIFTY_500;

        List<LocalDate> tradingDays = getTradingDays(startTime.toLocalDate(), endTime.toLocalDate());
        log.info("{} trading days, {} symbols", tradingDays.size(), symbols.size());

        List<QfTrade> allTrades = new ArrayList<>();

        for (String symbol : symbols) {
            List<CandleData> raw = candleRepo.findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
                symbol, "1min", startTime, endTime);
            if (raw.size() < 300) continue;

            Map<LocalDate, List<CandleData>> byDate = new LinkedHashMap<>();
            for (CandleData cd : raw) {
                byDate.computeIfAbsent(cd.getTimestamp().toLocalDate(), k -> new ArrayList<>()).add(cd);
            }

            for (LocalDate date : tradingDays) {
                List<CandleData> dayCandles = byDate.get(date);
                if (dayCandles == null || dayCandles.size() < 60) continue;

                List<Candle> candles = dayCandles.stream().map(this::toCandle).collect(Collectors.toList());

                // Walk candles in 5-minute increments, evaluate strategy
                for (int i = 60; i < candles.size(); i += 5) {
                    List<Candle> window = candles.subList(0, i + 1);
                    Candle latest = candles.get(i);
                    if (latest.timestamp() == null) continue;

                    BigDecimal vwap = computeVwap(dayCandles.subList(0, i + 1));
                    MarketContext ctx = new MarketContext(
                        symbol, window, latest.close(), vwap, Map.of(), Map.of("vwap", vwap));

                    Signal sig = plugin.evaluate(ctx, params);
                    if (sig == null || !sig.isValid()) continue;
                    if (sig.side() != Signal.Side.BUY) continue;

                    QfTrade trade = simulateExit(sig, candles, i + 1, capital, brokerage, latest.timestamp());
                    allTrades.add(trade);
                    i += 10; // skip ahead after entry
                }
            }
        }

        allTrades.sort(Comparator.comparing(t -> t.exitTime));

        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(aggregate(allTrades, capital, months));
        result.put("patternBreakdown", patternBreakdown(allTrades, capital));
        result.put("exitTimeBreakdown", exitTimeBreakdown(allTrades));
        result.put("monthlyBreakdown", monthlyBreakdown(allTrades));

        return ResponseEntity.ok(result);
    }

    @GetMapping("/model")
    public ResponseEntity<Map<String, Object>> model() {
        return ResponseEntity.ok(Map.of(
            "title", "QuickFlip Theoretical Model",
            "capitalPerTrade", CAPITAL_PER_TRADE,
            "brokeragePerTrade", BROKERAGE,
            "patterns", List.of(
                Map.of("name", "OPEN_DRIVE", "window", "9:15-9:30", "winRate", "60%",
                    "avgWin", "₹99", "avgLoss", "₹48", "targetPct", "0.8%", "holdMins", 15),
                Map.of("name", "VWAP_BOUNCE", "window", "9:45-14:30", "winRate", "63%",
                    "avgWin", "₹144", "avgLoss", "₹36", "targetPct", "1.2%", "holdMins", 29),
                Map.of("name", "VOL_EXPLOSION", "window", "9:30-14:30", "winRate", "55%",
                    "avgWin", "₹144", "avgLoss", "₹60", "targetPct", "1.2%", "holdMins", 25),
                Map.of("name", "RANGE_BREAK", "window", "14:00-15:00", "winRate", "67%",
                    "avgWin", "₹120", "avgLoss", "₹60", "targetPct", "1.2%", "holdMins", 22)
            ),
            "compositeWinRate", "60%",
            "monthlyExpectation", "₹8,750",
            "userMonthly", "₹6,563"
        ));
    }

    // ──── Exit simulation ────

    private QfTrade simulateExit(Signal sig, List<Candle> candles, int entryIdx,
                                  double capital, double brokerage, LocalDateTime tradeDate) {
        LocalTime entryTime = tradeDate.toLocalTime();
        double entry = sig.entryPrice().doubleValue();
        double sl = sig.stopLoss().doubleValue();
        double target = sig.target().doubleValue();
        double trailTrigger = sig.trailTriggerPct();
        double trailDist = sig.trailDistancePct();
        double peak = entry;
        boolean trailActive = false;
        String exitType = "TIME_EXIT";
        double exitPrice = entry;
        LocalTime exitTime = LocalTime.of(15, 30);

        for (int i = entryIdx; i < candles.size() && i < entryIdx + 120; i++) {
            Candle c = candles.get(i);
            double low = c.low().doubleValue();
            double high = c.high().doubleValue();
            if (high > peak) peak = high;

            if (!trailActive && (peak - entry) / entry * 100.0 >= trailTrigger)
                trailActive = true;

            double effectiveSl = trailActive
                ? peak * (1.0 - trailDist / 100.0) : sl;

            if (low <= effectiveSl) {
                exitPrice = effectiveSl;
                exitType = trailActive ? "TRAIL_HIT" : "SL_HIT";
                exitTime = c.timestamp() != null ? c.timestamp().toLocalTime() : exitTime;
                break;
            }
            if (high >= target) {
                exitPrice = target;
                exitType = "TARGET_HIT";
                exitTime = c.timestamp() != null ? c.timestamp().toLocalTime() : exitTime;
                break;
            }
        }

        double pct = (exitPrice - entry) / entry * 100.0;
        double gross = capital * pct / 100.0;
        String pattern = sig.reason() != null && sig.reason().contains("_")
            ? sig.reason().substring(0, sig.reason().indexOf(" "))
            : "UNKNOWN";

        return new QfTrade(sig.symbol(), pattern, entry, exitPrice, exitType,
            tradeDate, exitTime, entryTime, gross, gross - brokerage, pct);
    }

    // ──── Aggregation ────

    private Map<String, Object> aggregate(List<QfTrade> trades, double capital, int months) {
        if (trades.isEmpty()) return Map.of("trades", 0, "note", "no trades found");

        int wins = (int) trades.stream().filter(t -> t.netPnl > 0).count();
        double totalNet = trades.stream().mapToDouble(t -> t.netPnl).sum();
        double avgWin = trades.stream().filter(t -> t.netPnl > 0).mapToDouble(t -> t.netPnl).average().orElse(0);
        double avgLoss = trades.stream().filter(t -> t.netPnl < 0).mapToDouble(t -> t.netPnl).average().orElse(0);
        double maxWin = trades.stream().mapToDouble(t -> t.netPnl).max().orElse(0);
        double maxLoss = trades.stream().mapToDouble(t -> t.netPnl).min().orElse(0);

        double peak = 0, maxDd = 0, running = 0;
        for (QfTrade t : trades) { running += t.netPnl; if (running > peak) peak = running;
            double dd = peak - running; if (dd > maxDd) maxDd = dd; }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalTrades", trades.size());
        m.put("wins", wins);
        m.put("losses", trades.size() - wins);
        m.put("winRate", r2((double) wins / trades.size() * 100) + "%");
        m.put("totalNetPnl", r2(totalNet));
        m.put("avgPerTrade", r2(totalNet / trades.size()));
        m.put("avgWin", r2(avgWin));
        m.put("avgLoss", r2(avgLoss));
        m.put("maxWin", r2(maxWin));
        m.put("maxLoss", r2(maxLoss));
        m.put("maxDrawdown", r2(maxDd));
        m.put("monthlyPnl", r2(totalNet / months));
        m.put("totalInvested", r2(trades.size() * capital));
        return m;
    }

    private Map<String, Map<String, Object>> patternBreakdown(List<QfTrade> trades, double capital) {
        Map<String, List<QfTrade>> byPattern = trades.stream()
            .collect(Collectors.groupingBy(t -> t.pattern));
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (var e : byPattern.entrySet()) {
            result.put(e.getKey(), aggregate(e.getValue(), capital, 3));
        }
        return result;
    }

    private Map<String, Long> exitTimeBreakdown(List<QfTrade> trades) {
        Map<String, Long> breakdown = new LinkedHashMap<>();
        for (QfTrade t : trades) {
            String bucket = timeBucket(t.exitTime);
            breakdown.merge(bucket, 1L, Long::sum);
        }
        return breakdown;
    }

    private List<Map<String, Object>> monthlyBreakdown(List<QfTrade> trades) {
        Map<String, double[]> monthly = new LinkedHashMap<>();
        for (QfTrade t : trades) {
            String m = t.tradeDate.getYear() + "-" + String.format("%02d", t.tradeDate.getMonthValue());
            double[] d = monthly.computeIfAbsent(m, k -> new double[3]);
            d[0]++; d[1] += t.netPnl; if (t.netPnl > 0) d[2]++;
        }
        return monthly.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> {
            double[] d = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", e.getKey());
            m.put("trades", (int)d[0]);
            m.put("netPnl", r2(d[1]));
            m.put("winRate", r2(d[2]/d[0]*100) + "%");
            return m;
        }).collect(Collectors.toList());
    }

    // ──── Helpers ────

    private Candle toCandle(CandleData cd) {
        return new Candle(cd.getSymbol(), cd.getTimestamp(),
            cd.getOpen(), cd.getHigh(), cd.getLow(), cd.getClose(), cd.getVolume());
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

    private List<LocalDate> getTradingDays(LocalDate start, LocalDate end) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate d = start;
        while (!d.isAfter(end)) {
            if (d.getDayOfWeek().getValue() < 6) days.add(d);
            d = d.plusDays(1);
        }
        return days;
    }

    private String timeBucket(LocalTime t) {
        if (t == null) return "UNKNOWN";
        if (!t.isAfter(LocalTime.of(9, 30))) return "9:15-9:30";
        if (!t.isAfter(LocalTime.of(10, 30))) return "9:30-10:30";
        if (!t.isAfter(LocalTime.of(12, 0))) return "10:30-12:00";
        if (!t.isAfter(LocalTime.of(14, 0))) return "12:00-14:00";
        if (!t.isAfter(LocalTime.of(15, 0))) return "14:00-15:00";
        return "15:00-15:30";
    }

    private static double r2(double v) { return Math.round(v * 100.0) / 100.0; }

    record QfTrade(String symbol, String pattern, double entry, double exit,
                    String exitType, LocalDateTime tradeDate, LocalTime exitTime, LocalTime entryTime,
                    double grossPnl, double netPnl, double pnlPct) {}
}
