package com.stokr.engine;

import com.stokr.marketdata.Candle;
import com.stokr.external.ChartinkScannerService;
import com.stokr.strategy.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final ChartinkScannerService chartinkScannerService;

    private static final Map<String, String> STRATEGY_PLUGIN_MAP = new LinkedHashMap<>(Map.ofEntries(
        Map.entry("ORB_V",         "ORB_V"),
        Map.entry("ORB",           "ORB_V"),
        Map.entry("MORNING_SURGE", "MORNING_SURGE"),
        Map.entry("SURGE",         "MORNING_SURGE"),
        Map.entry("MORNING_SURGE_REVERSAL", "MORNING_SURGE_REVERSAL"),
        Map.entry("SURGE_REV",     "MORNING_SURGE_REVERSAL"),
        Map.entry("VWAP_TRIPLE",   "VWAP_TRIPLE"),
        Map.entry("VWAP",          "VWAP_TRIPLE"),
        Map.entry("TRADE_BOOK_IMBALANCE", "TRADE_BOOK_IMBALANCE"),
        Map.entry("TBI",           "TRADE_BOOK_IMBALANCE"),
        Map.entry("PRE_OPEN",      "PRE_OPEN"),
        Map.entry("PREOPEN",       "PRE_OPEN")
    ));

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

        java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");
        try {
            LocalDateTime startTime = dateStart != null
                ? LocalDateTime.parse(dateStart.replace("Z","").substring(0, 19))
                : LocalDateTime.now(IST).minusDays(30);
            LocalDateTime endTime = dateEnd != null
                ? LocalDateTime.parse(dateEnd.replace("Z","").substring(0, 19))
                : LocalDateTime.now(IST);

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
            java.time.ZoneId IST2 = java.time.ZoneId.of("Asia/Kolkata");
            LocalDateTime startTime = dateStart != null
                ? LocalDateTime.parse(dateStart.replace("Z","").substring(0, 19))
                : LocalDateTime.now(IST2).minusDays(30);
            LocalDateTime endTime = dateEnd != null
                ? LocalDateTime.parse(dateEnd.replace("Z","").substring(0, 19))
                : LocalDateTime.now(IST2);
            boolean useChartinkFilter = "CHARTINK".equalsIgnoreCase(universe);
            String resolvedUniverse = useChartinkFilter ? "NIFTY_100" : universe;

            List<String> chartinkSymbols = Collections.emptyList();
            if (useChartinkFilter) {
                chartinkSymbols = chartinkScannerService.fetchScannerSymbols(null);
                log.info("Chartink scan returned {} symbols", chartinkSymbols.size());
            }

            List<String> symbolList = symbols != null
                ? Arrays.asList(symbols.split(","))
                : (useChartinkFilter && !chartinkSymbols.isEmpty() ? chartinkSymbols : getSymbolsForUniverse(resolvedUniverse));

            String pluginType = resolvePluginType(strategy);
            StrategyPlugin plugin = findPlugin(pluginType);
            StrategyParams params = StrategyParams.defaults();

            log.info("Backtesting plugin={} on {} symbols from {}", pluginType, symbolList.size(), universe);
            Map<String, List<CandleData>> candlesBySymbol = new HashMap<>();
            for (String symbol : symbolList) {
                List<CandleData> candles = candleFetchService.fetchCandles(symbol, timeframe, startTime, endTime);
                if (!candles.isEmpty()) {
                    candlesBySymbol.put(symbol, candles);
                }
            }

            List<SimulatedTrade> allTrades = new ArrayList<>();
            for (Map.Entry<String, List<CandleData>> entry : candlesBySymbol.entrySet()) {
                allTrades.addAll(simulateStrategy(entry.getKey(), entry.getValue(), plugin, params));
            }
            allTrades.sort(java.util.Comparator.comparing(t -> t.entryTime));

            int totalTrades = allTrades.size();
            int winCount = 0, lossCount = 0;
            double totalPnl = 0;
            for (SimulatedTrade t : allTrades) {
                if (t.pnl > 0) winCount++;
                else if (t.pnl < 0) lossCount++;
                totalPnl += t.pnl;
            }

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

            String strategyName = plugin != null ? plugin.getStrategyType() : (strategy != null ? strategy : "ALL");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("strategy", strategyName);
            result.put("universe", universe);
            result.put("chartinkSymbols", useChartinkFilter ? symbolList.size() : 0);
            result.put("chartinkConfigured", chartinkScannerService.isConfigured());
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

            log.info("Backtest complete: strategy={} trades={} winRate={}% totalPnL={}",
                strategyName, totalTrades, Math.round(winRate * 10.0) / 10.0, Math.round(totalPnl));
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Advanced backtest failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Backtest failed: " + e.getMessage()));
        }
    }

    @GetMapping("/chartink-scan")
    public ResponseEntity<Map<String, Object>> getChartinkScan(
            @RequestParam(required = false) String scanClause) {
        List<String> symbols = chartinkScannerService.fetchScannerSymbols(scanClause);
        return ResponseEntity.ok(Map.of(
            "configured", chartinkScannerService.isConfigured(),
            "symbols", symbols,
            "count", symbols.size(),
            "scanClause", scanClause != null ? scanClause : com.stokr.external.ChartinkScannerService.DEFAULT_ORB_SCAN
        ));
    }

    private String resolvePluginType(String strategy) {
        if (strategy == null || strategy.isEmpty() || "ALL".equalsIgnoreCase(strategy))
            return "ORB_V";
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

        BigDecimal[] dayVwap = computePerDayVwap(candleData);

        BigDecimal[] dayOpenArr   = new BigDecimal[n];
        BigDecimal[] orbHighArr   = new BigDecimal[n];
        BigDecimal[] orbLowArr    = new BigDecimal[n];
        BigDecimal[] orbRangeArr  = new BigDecimal[n];
        {
            String curDay = null;
            int dayStart = 0;
            BigDecimal thisDayOpen = null;
            BigDecimal runOrbHigh = null, runOrbLow = null;
            boolean orbReady = false;

            for (int i = 0; i < n; i++) {
                String d = candleData.get(i).getTimestamp().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString();
                if (!d.equals(curDay)) {
                    curDay = d;
                    dayStart = i;
                    thisDayOpen = candles.get(i).open();
                    runOrbHigh = candles.get(i).high();
                    runOrbLow  = candles.get(i).low();
                    orbReady = false;
                }
                int candleInDay = i - dayStart;

                if (candleInDay < 15) {
                    runOrbHigh = runOrbHigh.max(candles.get(i).high());
                    runOrbLow  = runOrbLow.min(candles.get(i).low());
                }
                if (candleInDay == 14) {
                    orbReady = true;
                }

                dayOpenArr[i]   = thisDayOpen;
                orbHighArr[i]   = orbReady ? runOrbHigh : null;
                orbLowArr[i]    = orbReady ? runOrbLow  : null;
                orbRangeArr[i]  = orbReady ? runOrbHigh.subtract(runOrbLow) : null;
            }
        }

        java.util.Set<String> tradedDays = new java.util.HashSet<>();
        int openTradeExitIdx = -1;

        for (int i = 15; i < n; i++) {
            if (openTradeExitIdx > 0 && i <= openTradeExitIdx) continue;
            if (orbHighArr[i] == null) continue;

            String istDate = candleData.get(i).getTimestamp().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString();
            if (tradedDays.contains(istDate)) continue;

            java.time.LocalTime istTime = candleData.get(i).getTimestamp().atZone(ZoneId.of("Asia/Kolkata")).toLocalTime();

            Map<String, BigDecimal> indMap = new HashMap<>();
            BigDecimal rsi = indicators.get(i).rsi14();
            BigDecimal atr = indicators.get(i).atr14();
            BigDecimal volSma = indicators.get(i).volSma10();
            if (rsi != null) indMap.put("RSI14", rsi);
            if (atr != null) indMap.put("ATR14", atr);
            if (volSma != null) indMap.put("VOL_SMA_10", volSma);

            Map<String, Object> extras = new HashMap<>();
            extras.put("orbHigh",   orbHighArr[i]);
            extras.put("orbLow",    orbLowArr[i]);
            extras.put("orbRange",  orbRangeArr[i]);
            extras.put("dayOpen",   dayOpenArr[i]);
            extras.put("istHour",   istTime.getHour());
            extras.put("istMinute", istTime.getMinute());
            extras.put("vwap",      dayVwap[i]);
            extras.put("rsi14",     rsi);
            extras.put("atr14",     atr);
            extras.put("prevClose", i > 0 ? candleData.get(i - 1).getClose() : null);

            List<Candle> window = candles.subList(0, i + 1);
            MarketContext context = new MarketContext(symbol, window, candles.get(i).close(), dayVwap[i], indMap, extras);
            Signal signal = plugin != null ? plugin.evaluate(context, params) : null;

            if (signal != null && signal.isValid()) {
                tradedDays.add(istDate);
                SimulatedTrade trade = new SimulatedTrade(symbol, signal, i, candles.get(i).timestamp());
                java.time.LocalDate entryDate = candleData.get(i).getTimestamp().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();

                BigDecimal currentSL  = signal.stopLoss();
                BigDecimal bestPrice  = signal.entryPrice();
                double entryD = signal.entryPrice().doubleValue();
                boolean trailActivated = false;

                for (int j = i + 1; j < n; j++) {
                    Candle c = candles.get(j);
                    java.time.LocalDate exitDate = candleData.get(j).getTimestamp().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
                    boolean exited = false;

                    if (!exitDate.equals(entryDate)) {
                        Candle eod = candles.get(j - 1);
                        trade.exitAtPrice(j - 1, eod.timestamp(), "EOD_EXIT", eod.close());
                        exited = true;
                    } else {
                        if (signal.side() == Signal.Side.BUY) {
                            if (c.high().compareTo(bestPrice) > 0) {
                                bestPrice = c.high();
                                double gain = (bestPrice.doubleValue() - entryD) / entryD * 100;
                                if (gain >= 0.5) trailActivated = true;
                            }
                            if (trailActivated) {
                                BigDecimal newTrail = bestPrice.multiply(BigDecimal.valueOf(0.997))
                                    .setScale(2, java.math.RoundingMode.HALF_UP);
                                if (newTrail.compareTo(currentSL) > 0) currentSL = newTrail;
                            }
                        } else {
                            if (c.low().compareTo(bestPrice) < 0) {
                                bestPrice = c.low();
                                double gain = (entryD - bestPrice.doubleValue()) / entryD * 100;
                                if (gain >= 0.5) trailActivated = true;
                            }
                            if (trailActivated) {
                                BigDecimal newTrail = bestPrice.multiply(BigDecimal.valueOf(1.003))
                                    .setScale(2, java.math.RoundingMode.HALF_UP);
                                if (newTrail.compareTo(currentSL) < 0) currentSL = newTrail;
                            }
                        }

                        if (c.high().compareTo(signal.target()) >= 0) {
                            trade.exit(j, c.timestamp(), "TARGET_HIT");
                            exited = true;
                        } else if (c.low().compareTo(currentSL) <= 0) {
                            String exitLabel = trailActivated ? "TRAIL_SL" : "SL_HIT";
                            trade.exitAtPrice(j, c.timestamp(), exitLabel, currentSL);
                            exited = true;
                        }
                    }
                    if (exited) {
                        openTradeExitIdx = j;
                        break;
                    }
                }
                if (trade.exitType == null) {
                    Candle last = candles.get(n - 1);
                    trade.exitAtPrice(n - 1, last.timestamp(), "EOD_EXIT", last.close());
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
            cd.getTimestamp(),  // already LocalDateTime (IST)
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
                    movePct = -(entryPrice.subtract(stopLoss).doubleValue() / entryPrice.doubleValue());
                } else {
                    movePct = 0;
                }
                this.pnl = Math.round(movePct * CAPITAL * 100.0) / 100.0;
            }
        }

        void exitAtPrice(int exitIdx, LocalDateTime exitTime, String exitType, BigDecimal exitPrice) {
            this.exitIdx = exitIdx;
            this.exitTime = exitTime;
            this.exitType = exitType;
            if (entryPrice != null && entryPrice.compareTo(BigDecimal.ZERO) > 0 && exitPrice != null) {
                double movePct;
                if ("TARGET_HIT".equals(exitType)) {
                    movePct = target.subtract(entryPrice).doubleValue() / entryPrice.doubleValue();
                } else if ("SL_HIT".equals(exitType)) {
                    movePct = -(entryPrice.subtract(stopLoss).doubleValue() / entryPrice.doubleValue());
                } else {
                    movePct = (side == Signal.Side.BUY)
                        ? exitPrice.subtract(entryPrice).doubleValue() / entryPrice.doubleValue()
                        : entryPrice.subtract(exitPrice).doubleValue() / entryPrice.doubleValue();
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
