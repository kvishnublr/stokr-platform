package com.stokr.engine;

import com.stokr.marketdata.Candle;
import com.stokr.strategy.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final SignalRepository signalRepository;
    private final CandleFetchService candleFetchService;
    private final CandleDataRepository candleRepository;
    private final StrategyService strategyService;
    private final UniverseGroupService universeGroupService;
    private final List<StrategyPlugin> strategyPlugins;

    private static final Map<String, String> STRATEGY_PLUGIN_MAP = Map.of(
        "ORB", "ORB_V",
        "ADV_CASH", "TRADE_BOOK_IMBALANCE",
        "VWAP_SQUEEZE", "VWAP_TRIPLE",
        "GAP_FILL", "PRE_OPEN",
        "VWAP_BOUNCE", "VWAP_TRIPLE"
    );

    private static final double CAPITAL = 25000;

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runBacktest(
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false) String dateStart,
            @RequestParam(required = false) String dateEnd) {

        log.info("Running backtest: strategy={}, dateStart={}, dateEnd={}", strategy, dateStart, dateEnd);

        List<SignalEntity> signals = signalRepository.findAll();
        if (strategy != null && !strategy.isEmpty()) {
            signals = signals.stream()
                .filter(s -> s.getReason() != null && s.getReason().contains(strategy))
                .toList();
        }

        // Calculate backtest metrics
        double totalPnL = 0;
        int winCount = 0;
        int lossCount = 0;
        int totalTrades = signals.size();

        for (SignalEntity signal : signals) {
            if (signal.getExitType() != null) {
                if ("TARGET_HIT".equals(signal.getExitType())) {
                    winCount++;
                    if (signal.getTarget() != null && signal.getEntryPrice() != null) {
                        double pnl = (signal.getTarget().doubleValue() - signal.getEntryPrice().doubleValue())
                            / signal.getEntryPrice().doubleValue() * 5000;
                        totalPnL += pnl;
                    }
                } else if ("SL_HIT".equals(signal.getExitType())) {
                    lossCount++;
                    if (signal.getStopLoss() != null && signal.getEntryPrice() != null) {
                        double loss = (signal.getEntryPrice().doubleValue() - signal.getStopLoss().doubleValue())
                            / signal.getEntryPrice().doubleValue() * 5000;
                        totalPnL -= loss;
                    }
                }
            }
        }

        double winRate = totalTrades > 0 ? (double) winCount / totalTrades * 100 : 0;
        double avgPnL = totalTrades > 0 ? totalPnL / totalTrades : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", strategy != null ? strategy : "ALL");
        result.put("totalTrades", totalTrades);
        result.put("winCount", winCount);
        result.put("lossCount", lossCount);
        result.put("totalPnL", Math.round(totalPnL * 100.0) / 100.0);
        result.put("winRate", Math.round(winRate * 100.0) / 100.0);
        result.put("avgPnL", Math.round(avgPnL * 100.0) / 100.0);
        result.put("maxDrawdown", calculateMaxDrawdown(signals));
        result.put("profitFactor", calculateProfitFactor(signals));

        log.info("Backtest complete: {}", result);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/load-data")
    public ResponseEntity<Map<String, Object>> loadHistoricalData(
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false) String dateStart,
            @RequestParam(required = false) String dateEnd,
            @RequestParam(defaultValue = "daily") String timeframe) {

        log.info("Loading historical data: strategy={}, dateStart={}, dateEnd={}, timeframe={}",
                strategy, dateStart, dateEnd, timeframe);

        try {
            Instant startTime = dateStart != null ? Instant.parse(dateStart) : Instant.now().minusSeconds(2592000);
            Instant endTime = dateEnd != null ? Instant.parse(dateEnd) : Instant.now();

            List<String> symbolList = getSymbolsForUniverse("NIFTY_100");

            List<String> failedSymbols = new ArrayList<>();
            int totalCandles = 0;

            for (String symbol : symbolList) {
                List<CandleData> existing = candleRepository
                    .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(symbol, timeframe, startTime, endTime);
                if (!existing.isEmpty()) {
                    totalCandles += existing.size();
                    log.info("Found {} cached candles for {}/{}", existing.size(), symbol, timeframe);
                    continue;
                }

                List<CandleData> candles = candleFetchService.fetchCandles(symbol, timeframe, startTime, endTime);
                if (candles.isEmpty()) {
                    log.warn("No candles for {}, skipping", symbol);
                } else {
                    totalCandles += candles.size();
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("strategy", strategy != null ? strategy : "ALL");
            result.put("totalCandles", totalCandles);
            result.put("symbols", symbolList);
            result.put("failedSymbols", failedSymbols);
            result.put("dateRange", Map.of("start", startTime.toString(), "end", endTime.toString()));

            log.info("Data loading complete: {}", result);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Data loading failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Data loading failed: " + e.getMessage()));
        }
    }

    @PostMapping("/advanced")
    public ResponseEntity<Map<String, Object>> runAdvancedBacktest(
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false) String dateStart,
            @RequestParam(required = false) String dateEnd,
            @RequestParam(required = false) String symbols,
            @RequestParam(defaultValue = "1min") String timeframe,
            @RequestParam(defaultValue = "NIFTY_100") String universe) {

        log.info("Running advanced backtest: strategy={}, universe={}, dateStart={}, dateEnd={}, timeframe={}",
                strategy, universe, dateStart, dateEnd, timeframe);

        try {
            Instant startTime = dateStart != null ? Instant.parse(dateStart) : Instant.now().minusSeconds(2592000);
            Instant endTime = dateEnd != null ? Instant.parse(dateEnd) : Instant.now();
            List<String> symbolList = symbols != null
                ? Arrays.asList(symbols.split(","))
                : getSymbolsForUniverse(universe);

            String pluginType = resolvePluginType(strategy);
            StrategyPlugin plugin = findPlugin(pluginType);
            StrategyParams params = StrategyParams.defaults();

            log.info("Loading candles for {} symbols from universe {}", symbolList.size(), universe);
            Map<String, List<CandleData>> candlesBySymbol = new HashMap<>();
            for (String symbol : symbolList) {
                List<CandleData> candles = candleFetchService.fetchCandles(symbol, timeframe, startTime, endTime);
                if (!candles.isEmpty()) {
                    candlesBySymbol.put(symbol, candles);
                } else {
                    log.debug("No candles for {}", symbol);
                }
            }

            List<SimulatedTrade> allTrades = new ArrayList<>();
            for (Map.Entry<String, List<CandleData>> entry : candlesBySymbol.entrySet()) {
                allTrades.addAll(simulateStrategy(entry.getKey(), entry.getValue(), plugin, params));
            }
            // Sort trades by entry time for daily bucketing
            allTrades.sort(java.util.Comparator.comparing(t -> t.entryTime));

            int totalTrades = allTrades.size();
            int winCount = 0, lossCount = 0;
            double totalPnl = 0;
            for (SimulatedTrade t : allTrades) {
                if ("TARGET_HIT".equals(t.exitType)) winCount++;
                else if ("SL_HIT".equals(t.exitType)) lossCount++;
                totalPnl += t.pnl;
            }

            // Daily P&L metrics
            java.util.TreeMap<java.time.LocalDate, Double> dailyPnl = new java.util.TreeMap<>();
            for (SimulatedTrade t : allTrades) {
                if (t.entryTime == null) continue;
                java.time.LocalDate d = t.entryTime.toLocalDate();
                dailyPnl.merge(d, t.pnl, Double::sum);
            }
            double maxProfitDay = dailyPnl.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double maxLossDay   = dailyPnl.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double avgProfitDay = dailyPnl.isEmpty() ? 0
                : dailyPnl.values().stream().mapToDouble(Double::doubleValue).sum() / dailyPnl.size();
            int profitDays = (int) dailyPnl.values().stream().filter(p -> p > 0).count();
            int lossDays   = (int) dailyPnl.values().stream().filter(p -> p < 0).count();

            double winRate = totalTrades > 0 ? (double) winCount / totalTrades * 100 : 0;
            double avgPnl  = totalTrades > 0 ? totalPnl / totalTrades : 0;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("strategy", strategy != null ? strategy : "ALL");
            result.put("universe", universe);
            result.put("symbolsLoaded", candlesBySymbol.size());
            result.put("totalTrades", totalTrades);
            result.put("winCount", winCount);
            result.put("lossCount", lossCount);
            result.put("totalPnL",       Math.round(totalPnl * 100.0) / 100.0);
            result.put("winRate",        Math.round(winRate * 100.0) / 100.0);
            result.put("avgPnL",         Math.round(avgPnl * 100.0) / 100.0);
            result.put("maxDrawdown",    calculateMaxDrawdownFromTrades(allTrades));
            result.put("profitFactor",   calculateProfitFactorFromTrades(allTrades));
            result.put("maxProfitDay",   Math.round(maxProfitDay * 100.0) / 100.0);
            result.put("maxLossDay",     Math.round(maxLossDay * 100.0) / 100.0);
            result.put("avgProfitDay",   Math.round(avgProfitDay * 100.0) / 100.0);
            result.put("profitDays",     profitDays);
            result.put("lossDays",       lossDays);
            result.put("totalTradingDays", dailyPnl.size());
            result.put("candlesLoaded",  candlesBySymbol.values().stream().mapToInt(List::size).sum());
            result.put("capitalPerTrade", CAPITAL);
            result.put("dateRange", Map.of("start", startTime.toString(), "end", endTime.toString()));
            result.put("trades", allTrades.stream().map(SimulatedTrade::toMap).toList());

            log.info("Advanced backtest complete: strategy={} trades={} winRate={}% totalPnL={}",
                strategy, totalTrades, Math.round(winRate * 10.0) / 10.0, Math.round(totalPnl));
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Advanced backtest failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Backtest failed: " + e.getMessage()));
        }
    }

    private String resolvePluginType(String strategy) {
        if (strategy == null || strategy.isEmpty() || "ALL".equals(strategy)) return "ORB_V";
        String mapped = STRATEGY_PLUGIN_MAP.get(strategy.toUpperCase());
        return mapped != null ? mapped : "ORB_V";
    }

    private StrategyPlugin findPlugin(String type) {
        for (StrategyPlugin p : strategyPlugins) {
            if (p.getStrategyType().equals(type)) return p;
        }
        return strategyPlugins.isEmpty() ? null : strategyPlugins.get(0);
    }

    private List<SimulatedTrade> simulateStrategy(String symbol, List<CandleData> candleData, StrategyPlugin plugin, StrategyParams params) {
        List<SimulatedTrade> trades = new ArrayList<>();
        int n = candleData.size();
        if (n < 20) return trades;

        List<Candle> candles = candleData.stream().map(this::toCandle).toList();
        List<IndicatorUtils.Indicators> indicators = IndicatorUtils.computeAll(candleData);

        // Pre-compute per-day VWAP (resets at each trading day boundary)
        BigDecimal[] dayVwap = computePerDayVwap(candleData);
        // Pre-compute day open prices and prev-day close for gap calculations
        BigDecimal[] dayOpen = new BigDecimal[n];
        BigDecimal[] prevDayClose = new BigDecimal[n];
        {
            String curDay = null;
            BigDecimal lastDayClose = null;
            BigDecimal thisDayOpen = null;
            for (int i = 0; i < n; i++) {
                String d = candleData.get(i).getTimestamp().atZone(ZoneId.of("Asia/Kolkata"))
                    .toLocalDate().toString();
                if (!d.equals(curDay)) {
                    lastDayClose = curDay != null && i > 0 ? candles.get(i - 1).close() : null;
                    thisDayOpen = candles.get(i).open();
                    curDay = d;
                }
                dayOpen[i] = thisDayOpen;
                prevDayClose[i] = lastDayClose;
            }
        }

        int openTradeExitIdx = -1;

        for (int i = 14; i < n; i++) {
            if (openTradeExitIdx > 0 && i <= openTradeExitIdx) continue;

            List<Candle> window = candles.subList(0, i + 1);
            BigDecimal vwap = dayVwap[i];   // per-day VWAP
            BigDecimal rsi = indicators.get(i).rsi14();
            BigDecimal atr = indicators.get(i).atr14();

            Map<String, BigDecimal> indMap = new HashMap<>();
            if (rsi != null) indMap.put("RSI14", rsi);
            if (atr != null) indMap.put("ATR14", atr);

            // Volume-based unfilled ratio proxy: volume relative to recent avg
            int volStart = Math.max(0, i - 9);
            long sumVol = 0;
            for (int v = volStart; v <= i; v++) sumVol += candles.get(v).volume();
            long avgVol = sumVol / (i - volStart + 1);
            double volRatio = avgVol > 0 ? (double) candles.get(i).volume() / avgVol : 1.0;
            // unfilledRatio > 0.35 when volume >= average (proxy for pre-open order imbalance)
            BigDecimal unfilledRatio = BigDecimal.valueOf(Math.min(1.0, volRatio * 0.4));

            // Gap from prev day close to current day open
            BigDecimal gapPct = null;
            if (prevDayClose[i] != null && prevDayClose[i].compareTo(BigDecimal.ZERO) > 0) {
                gapPct = dayOpen[i].subtract(prevDayClose[i])
                    .divide(prevDayClose[i], 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            }

            // IST time-of-day for time-gated strategies
            java.time.LocalTime istTime = candleData.get(i).getTimestamp()
                .atZone(ZoneId.of("Asia/Kolkata")).toLocalTime();

            Map<String, Object> extras = new HashMap<>();
            extras.put("buyerQty", 100L);
            extras.put("sellerQty", 40L);
            extras.put("prevClose", i > 0 ? candles.get(i - 1).close() : candles.get(i).close());
            extras.put("unfilledRatio", unfilledRatio);
            if (gapPct != null) extras.put("gapPct", gapPct);
            extras.put("atrThresholdPct", BigDecimal.valueOf(0.05));
            extras.put("istHour", istTime.getHour());
            extras.put("istMinute", istTime.getMinute());
            if (dayOpen[i] != null) extras.put("dayOpen", dayOpen[i]);

            MarketContext context = new MarketContext(symbol, window, candles.get(i).close(), vwap, indMap, extras);
            Signal signal = plugin.evaluate(context, params);
            if (signal != null && signal.isValid()) {
                SimulatedTrade trade = new SimulatedTrade(symbol, signal, i, candles.get(i).timestamp());
                for (int j = i + 1; j < n; j++) {
                    Candle c = candles.get(j);
                    boolean exited = false;
                    if (signal.side() == Signal.Side.BUY) {
                        if (c.high().compareTo(signal.target()) >= 0) {
                            trade.exit(j, c.timestamp(), "TARGET_HIT");
                            exited = true;
                        } else if (c.low().compareTo(signal.stopLoss()) <= 0) {
                            trade.exit(j, c.timestamp(), "SL_HIT");
                            exited = true;
                        }
                    } else {
                        if (c.low().compareTo(signal.target()) <= 0) {
                            trade.exit(j, c.timestamp(), "TARGET_HIT");
                            exited = true;
                        } else if (c.high().compareTo(signal.stopLoss()) >= 0) {
                            trade.exit(j, c.timestamp(), "SL_HIT");
                            exited = true;
                        }
                    }
                    if (exited) {
                        openTradeExitIdx = j;
                        break;
                    }
                }
                if (trade.exitType == null) {
                    trade.exit(n - 1, candles.get(n - 1).timestamp(), "NO_EXIT");
                }
                trades.add(trade);
            }
        }
        return trades;
    }

    private BigDecimal[] computePerDayVwap(List<CandleData> candles) {
        int n = candles.size();
        BigDecimal[] result = new BigDecimal[n];
        String curDay = null;
        BigDecimal cumTpv = BigDecimal.ZERO;
        long cumVol = 0;
        for (int i = 0; i < n; i++) {
            String d = candles.get(i).getTimestamp().atZone(ZoneId.of("Asia/Kolkata"))
                .toLocalDate().toString();
            if (!d.equals(curDay)) {
                curDay = d;
                cumTpv = BigDecimal.ZERO;
                cumVol = 0;
            }
            CandleData c = candles.get(i);
            BigDecimal tp = c.getHigh().add(c.getLow()).add(c.getClose())
                .divide(BigDecimal.valueOf(3), 4, java.math.RoundingMode.HALF_UP);
            cumTpv = cumTpv.add(tp.multiply(BigDecimal.valueOf(c.getVolume())));
            cumVol += c.getVolume();
            result[i] = cumVol > 0
                ? cumTpv.divide(BigDecimal.valueOf(cumVol), 4, java.math.RoundingMode.HALF_UP)
                : c.getClose();
        }
        return result;
    }

    private Candle toCandle(CandleData cd) {
        return new Candle(
            cd.getSymbol(),
            LocalDateTime.ofInstant(cd.getTimestamp(), ZoneId.systemDefault()),
            cd.getOpen(), cd.getHigh(), cd.getLow(), cd.getClose(), cd.getVolume()
        );
    }

    private static class SimulatedTrade {
        String symbol;
        Signal.Side side;
        BigDecimal entryPrice, stopLoss, target;
        int entryIdx, exitIdx;
        LocalDateTime entryTime, exitTime;
        String exitType;
        double pnl;

        SimulatedTrade(String symbol, Signal signal, int entryIdx, LocalDateTime entryTime) {
            this.symbol = symbol;
            this.side = signal.side();
            this.entryPrice = signal.entryPrice();
            this.stopLoss = signal.stopLoss();
            this.target = signal.target();
            this.entryIdx = entryIdx;
            this.entryTime = entryTime;
        }

        void exit(int exitIdx, LocalDateTime exitTime, String exitType) {
            this.exitIdx = exitIdx;
            this.exitTime = exitTime;
            this.exitType = exitType;
            if (entryPrice != null && entryPrice.compareTo(BigDecimal.ZERO) > 0) {
                double movePct;
                if ("TARGET_HIT".equals(exitType)) {
                    movePct = target.subtract(entryPrice).doubleValue() / entryPrice.doubleValue();
                } else if ("SL_HIT".equals(exitType)) {
                    movePct = entryPrice.subtract(stopLoss).doubleValue() / entryPrice.doubleValue();
                    movePct = -movePct;
                } else {
                    movePct = 0;
                }
                this.pnl = Math.round(movePct * CAPITAL * 100.0) / 100.0;
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", symbol);
            m.put("side", side);
            m.put("entryPrice", entryPrice);
            m.put("stopLoss", stopLoss);
            m.put("target", target);
            m.put("entryTime", entryTime != null ? entryTime.toString() : null);
            m.put("exitTime", exitTime != null ? exitTime.toString() : null);
            m.put("exitType", exitType);
            m.put("pnl", pnl);
            return m;
        }
    }

    private double calculateMaxDrawdownFromTrades(List<SimulatedTrade> trades) {
        double peak = 0, drawdown = 0, running = 0;
        for (SimulatedTrade t : trades) {
            running += t.pnl;
            peak = Math.max(peak, running);
            double dd = peak > 0 ? (peak - running) / peak * 100 : 0;
            drawdown = Math.max(drawdown, dd);
        }
        return Math.round(drawdown * 100.0) / 100.0;
    }

    private double calculateProfitFactorFromTrades(List<SimulatedTrade> trades) {
        double grossProfit = 0, grossLoss = 0;
        for (SimulatedTrade t : trades) {
            if (t.pnl > 0) grossProfit += t.pnl;
            else grossLoss += Math.abs(t.pnl);
        }
        return grossLoss > 0 ? grossProfit / grossLoss : (grossProfit > 0 ? 999 : 0);
    }

    private List<String> getSymbolsForUniverse(String universe) {
        return universeGroupService.findByKey(universe)
                .map(g -> universeGroupService.resolveSymbolsForGroup(g.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown universe: " + universe));
    }

    private double calculateMaxDrawdown(List<SignalEntity> signals) {
        double peak = 0;
        double drawdown = 0;
        double runningBalance = 0;

        for (SignalEntity signal : signals) {
            if (signal.getExitType() != null && signal.getEntryPrice() != null) {
                double tradePnL = 0;
                if ("TARGET_HIT".equals(signal.getExitType()) && signal.getTarget() != null) {
                    tradePnL = (signal.getTarget().doubleValue() - signal.getEntryPrice().doubleValue());
                } else if ("SL_HIT".equals(signal.getExitType()) && signal.getStopLoss() != null) {
                    tradePnL = -(signal.getEntryPrice().doubleValue() - signal.getStopLoss().doubleValue());
                }

                runningBalance += tradePnL;
                peak = Math.max(peak, runningBalance);
                double currentDD = (peak - runningBalance) / Math.max(peak, 1);
                drawdown = Math.max(drawdown, currentDD);
            }
        }

        return Math.round(drawdown * 10000.0) / 100.0;
    }

    private double calculateProfitFactor(List<SignalEntity> signals) {
        double grossProfit = 0;
        double grossLoss = 0;

        for (SignalEntity signal : signals) {
            if (signal.getExitType() != null && signal.getEntryPrice() != null) {
                if ("TARGET_HIT".equals(signal.getExitType()) && signal.getTarget() != null) {
                    grossProfit += (signal.getTarget().doubleValue() - signal.getEntryPrice().doubleValue());
                } else if ("SL_HIT".equals(signal.getExitType()) && signal.getStopLoss() != null) {
                    grossLoss += (signal.getEntryPrice().doubleValue() - signal.getStopLoss().doubleValue());
                }
            }
        }

        return grossLoss > 0 ? grossProfit / grossLoss : (grossProfit > 0 ? 999 : 0);
    }
}
