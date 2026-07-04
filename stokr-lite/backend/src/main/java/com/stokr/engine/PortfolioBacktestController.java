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
 * Unified portfolio backtest — all 4 cash strategies with ₹1L allocation.
 *
 * Capital:
 *   QuickFlip: 3×₹12K | BTST: 1×₹20K | 3-Day Swing: 1×₹25K | 20D Breakout: 1×₹19K
 */
@Slf4j
@RestController
@RequestMapping("/api/backtest/portfolio")
@RequiredArgsConstructor
public class PortfolioBacktestController {

    private final CandleDataRepository candleRepo;
    private final List<StrategyPlugin> plugins;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final double BROKERAGE = 40.0;

    record Alloc(String name, String type, int positions, double capital,
                 String timeframe, int minCandles) {}

    private static final List<Alloc> ALLOCATIONS = List.of(
        new Alloc("Momentum Surge", "MOMENTUM_SURGE", 2, 12000, "1min", 60),
        new Alloc("BTST", "BTST", 1, 20000, "1min", 30),
        new Alloc("3-Day Swing", "3_DAY_MOMENTUM_SWING", 1, 25000, "day", 10),
        new Alloc("20D Breakout", "20_DAY_BREAKOUT", 1, 19000, "day", 10)
    );

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runPortfolioBacktest(
            @RequestParam(defaultValue = "3") int months) {

        if (months < 1 || months > 12)
            return ResponseEntity.badRequest().body(Map.of("error", "months 1-12"));

        LocalDateTime endTime = LocalDateTime.now(IST);
        LocalDateTime startTime = endTime.minusMonths(months);

        log.info("=== Portfolio backtest: {} months ===", months);

        List<String> symbols = candleRepo.findAllSymbols();
        if (symbols.isEmpty()) symbols = com.stokr.marketdata.ZerodhaLiveDataScheduler.NIFTY_500;
        List<LocalDate> tradingDays = getTradingDays(startTime.toLocalDate(), endTime.toLocalDate());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("months", months);
        result.put("totalCapital", 100000);
        result.put("dateRange", startTime + " to " + endTime);
        result.put("tradingDays", tradingDays.size());

        Map<String, Map<String, Object>> stratResults = new LinkedHashMap<>();
        double portfolioNet = 0;
        int totalTrades = 0;

        for (Alloc alloc : ALLOCATIONS) {
            StrategyPlugin plugin = plugins.stream()
                .filter(p -> alloc.type.equals(p.getStrategyType()))
                .findFirst().orElse(null);

            if (plugin == null) {
                stratResults.put(alloc.name, Map.of("error", "plugin not found"));
                continue;
            }

            StratResult sr = backtestStrategy(plugin, symbols, tradingDays, alloc, startTime, endTime);
            stratResults.put(alloc.name, sr.toMap(3));
            portfolioNet += sr.totalNet;
            totalTrades += sr.trades;
        }

        result.put("strategies", stratResults);
        result.put("totalTrades", totalTrades);
        result.put("totalNetPnl", r2(portfolioNet));
        result.put("monthlyPnl", r2(portfolioNet / months));
        result.put("userProfit75", r2(portfolioNet * 0.75));
        result.put("adminFee25", r2(portfolioNet * 0.25));
        result.put("userMonthlyRoi20k", r2(portfolioNet * 0.75 / months / 20000 * 100) + "%");

        return ResponseEntity.ok(result);
    }

    @GetMapping("/model")
    public ResponseEntity<Map<String, Object>> theoreticalModel() {
        List<Map<String, Object>> strats = List.of(
            Map.of("name", "QuickFlip", "capital", "₹36K (3×₹12K)", "signalsMonth", 250,
                "wr", "60%", "avgWin", "₹150", "avgLoss", "₹50", "expectancyTrade", "₹35",
                "monthly", "₹8,750"),
            Map.of("name", "BTST", "capital", "₹20K (1×₹20K)", "signalsMonth", 34,
                "wr", "58%", "avgWin", "₹400", "avgLoss", "₹220", "expectancyTrade", "₹65",
                "monthly", "₹2,210"),
            Map.of("name", "3-Day Swing", "capital", "₹25K (1×₹25K)", "signalsMonth", 8,
                "wr", "50%", "avgWin", "₹1,250", "avgLoss", "₹600", "expectancyTrade", "₹275",
                "monthly", "₹2,200"),
            Map.of("name", "20D Breakout", "capital", "₹19K (1×₹19K)", "signalsMonth", 6,
                "wr", "55%", "avgWin", "₹1,800", "avgLoss", "₹700", "expectancyTrade", "₹283",
                "monthly", "₹1,700")
        );

        double total = 8750 + 2210 + 2200 + 1700;
        return ResponseEntity.ok(Map.of(
            "totalCapital", 100000,
            "userDeposit", 20000,
            "strategies", strats,
            "totalMonthly", r2(total),
            "userMonthly", r2(total * 0.75),
            "adminMonthly", r2(total * 0.25),
            "userMonthlyRoi", r2(total * 0.75 / 20000 * 100) + "%"
        ));
    }

    // ──── Per-strategy backtest ────

    private StratResult backtestStrategy(StrategyPlugin plugin, List<String> symbols,
                                          List<LocalDate> tradingDays, Alloc alloc,
                                          LocalDateTime start, LocalDateTime end) {
        StratResult sr = new StratResult(alloc.name);
        StrategyParams params = StrategyParams.defaults();

        for (String symbol : symbols) {
            try {
                List<CandleData> raw = candleRepo.findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
                    symbol, alloc.timeframe, start, end);
                if (raw.size() < alloc.minCandles * 5) continue;

                Map<LocalDate, List<CandleData>> byDate = new LinkedHashMap<>();
                for (CandleData cd : raw)
                    byDate.computeIfAbsent(cd.getTimestamp().toLocalDate(), k -> new ArrayList<>()).add(cd);

                for (LocalDate date : tradingDays) {
                    List<CandleData> dayData = byDate.get(date);
                    if (dayData == null || dayData.size() < alloc.minCandles) continue;

                    List<Candle> candles = dayData.stream().map(this::toCandle).collect(Collectors.toList());

                    if ("1min".equals(alloc.timeframe)) {
                        // Intraday: walk candles, evaluate every 5 min
                        for (int i = alloc.minCandles; i < candles.size(); i += 5) {
                            Candle latest = candles.get(i);
                            if (latest.timestamp() == null) continue;
                            List<Candle> window = candles.subList(0, i + 1);

                            MarketContext ctx = buildContext(symbol, window, latest,
                                dayData.subList(0, i + 1));
                            Signal sig = plugin.evaluate(ctx, params);
                            if (sig == null || !sig.isValid() || sig.side() != Signal.Side.BUY) continue;

                            double pnl = simulateExit(sig, candles, i + 1, alloc.capital);
                            if (!Double.isNaN(pnl)) sr.addTrade(pnl);
                            i += 10;
                        }
                    } else {
                        // Daily: evaluate once per day
                        Candle latest = candles.get(candles.size() - 1);
                        MarketContext ctx = buildContext(symbol, candles, latest, dayData);
                        Signal sig = plugin.evaluate(ctx, params);
                        if (sig == null || !sig.isValid() || sig.side() != Signal.Side.BUY) continue;

                        double pnl = simulateExit(sig, candles, candles.size(), alloc.capital);
                        if (!Double.isNaN(pnl)) sr.addTrade(pnl);
                    }
                }
            } catch (Exception e) {
                // skip
            }
        }

        return sr;
    }

    private MarketContext buildContext(String symbol, List<Candle> candles, Candle latest,
                                        List<CandleData> rawSubList) {
        BigDecimal vwap = computeVwap(rawSubList);
        return new MarketContext(symbol, candles, latest.close(), vwap, Map.of(), Map.of("vwap", vwap));
    }

    private double simulateExit(Signal sig, List<Candle> candles, int entryIdx, double capital) {
        double entry = sig.entryPrice().doubleValue();
        double sl = sig.stopLoss().doubleValue();
        double target = sig.target().doubleValue();
        double trailTrigger = sig.trailTriggerPct();
        double trailDist = sig.trailDistancePct();
        double peak = entry;
        boolean trailActive = false;

        for (int i = entryIdx; i < candles.size(); i++) {
            Candle c = candles.get(i);
            double high = c.high().doubleValue(), low = c.low().doubleValue();
            if (high > peak) peak = high;

            if (!trailActive && (peak - entry) / entry * 100.0 >= trailTrigger)
                trailActive = true;

            double effectiveSl = trailActive
                ? peak * (1.0 - trailDist / 100.0) : sl;

            if (low <= effectiveSl)
                return capital * (effectiveSl - entry) / entry - BROKERAGE;
            if (high >= target)
                return capital * (target - entry) / entry - BROKERAGE;
        }
        double lastClose = candles.get(candles.size() - 1).close().doubleValue();
        return capital * (lastClose - entry) / entry - BROKERAGE;
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

    private static double r2(double v) { return Math.round(v * 100.0) / 100.0; }

    static class StratResult {
        final String name;
        int trades;
        double totalNet;
        int wins;
        double maxWin, maxLoss, peak, maxDd;

        StratResult(String name) { this.name = name; }

        void addTrade(double pnl) {
            trades++;
            totalNet += pnl;
            if (pnl > 0) wins++;
            if (pnl > maxWin) maxWin = pnl;
            if (pnl < maxLoss) maxLoss = pnl;
            if (totalNet > peak) peak = totalNet;
            double dd = peak - totalNet;
            if (dd > maxDd) maxDd = dd;
        }

        Map<String, Object> toMap(int months) {
            if (trades == 0) return Map.of("trades", 0, "note", "no trades");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trades", trades);
            m.put("wins", wins);
            m.put("losses", trades - wins);
            m.put("winRate", r2((double) wins / trades * 100) + "%");
            m.put("totalNetPnl", r2(totalNet));
            m.put("avgPerTrade", r2(totalNet / trades));
            m.put("maxWin", r2(maxWin));
            m.put("maxLoss", r2(maxLoss));
            m.put("maxDrawdown", r2(maxDd));
            m.put("monthlyPnl", r2(totalNet / months));
            return m;
        }
    }
}
