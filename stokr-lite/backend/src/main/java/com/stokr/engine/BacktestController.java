package com.stokr.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.marketdata.Candle;
import com.stokr.external.ChartinkScannerService;
import com.stokr.external.ZerodhaCandleService;
import com.stokr.strategy.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
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
    private final PairsTradingService pairsTradingService;
    private final BacktestCacheRepository backtestCacheRepository;
    private final ObjectMapper objectMapper;
    private final ZerodhaCandleService zerodhaCandleService;

    private static final Map<String, String> STRATEGY_PLUGIN_MAP = new LinkedHashMap<>(Map.ofEntries(
        Map.entry("MORNING_SURGE_REVERSAL", "MORNING_SURGE_REVERSAL"),
        Map.entry("SURGE_REV",     "MORNING_SURGE_REVERSAL"),
        Map.entry("VWAP_REVERSION", "VWAP_REVERSION"),
        Map.entry("VWAP_REV",      "VWAP_REVERSION"),
        Map.entry("REVERSION",     "VWAP_REVERSION"),
        Map.entry("SMART_MONEY_FLOW", "SMART_MONEY_FLOW"),
        Map.entry("SMF",           "SMART_MONEY_FLOW"),
        Map.entry("MOMENTUM_TRAIL", "MOMENTUM_TRAIL"),
        Map.entry("MT",            "MOMENTUM_TRAIL"),
        Map.entry("GAP_REVERSAL",  "GAP_REVERSAL"),
        Map.entry("GAP_REV",       "GAP_REVERSAL"),
        Map.entry("VOLUME_SPIKE_MOMENTUM", "VOLUME_SPIKE_MOMENTUM"),
        Map.entry("VSM",           "VOLUME_SPIKE_MOMENTUM"),
        Map.entry("ORB_BREAKOUT_LONG", "ORB_BREAKOUT_LONG"),
        Map.entry("OBL",           "ORB_BREAKOUT_LONG"),
        Map.entry("ORB_RETEST_LONG", "ORB_RETEST_LONG"),
        Map.entry("ORL",           "ORB_RETEST_LONG"),
        Map.entry("VWAP_BOUNCE_LONG", "VWAP_BOUNCE_LONG"),
        Map.entry("VBL",           "VWAP_BOUNCE_LONG"),
        Map.entry("GAP_VWAP_RETEST", "GAP_VWAP_RETEST"),
        Map.entry("GVR",           "GAP_VWAP_RETEST"),
        Map.entry("INTRADAY_HIGH_BREAKOUT", "INTRADAY_HIGH_BREAKOUT"),
        Map.entry("IHB",           "INTRADAY_HIGH_BREAKOUT"),
        Map.entry("VWAP_BOUNCE_V2", "VWAP_BOUNCE_V2"),
        Map.entry("VBL2",          "VWAP_BOUNCE_V2"),
        Map.entry("SECTOR_ORB",          "SECTOR_ORB"),
        Map.entry("SORB",               "SECTOR_ORB"),
        Map.entry("THREE_DAY_MOMENTUM", "THREE_DAY_MOMENTUM"),
        Map.entry("3DM",                "THREE_DAY_MOMENTUM")
    ));

    private static double CAPITAL = 25000;

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
            @RequestParam(defaultValue = "NIFTY_100") String universe,
            @RequestParam(defaultValue = "0") int brokerage,
            @RequestParam(defaultValue = "25000") double capital) {

        log.info("Running advanced backtest: strategy={}, universe={}, dateStart={}, dateEnd={}, timeframe={}, capital={}",
                strategy, universe, dateStart, dateEnd, timeframe, capital);

        try {
            CAPITAL = capital;
            java.time.ZoneId IST2 = java.time.ZoneId.of("Asia/Kolkata");
            LocalDateTime startTime = dateStart != null
                ? parseDateParam(dateStart)
                : LocalDateTime.now(IST2).minusDays(30);
            LocalDateTime endTime = dateEnd != null
                ? parseDateParam(dateEnd)
                : LocalDateTime.now(IST2);

            String pluginType = resolvePluginType(strategy);
            String cacheKey = computeCacheKey(pluginType, universe, startTime, endTime, brokerage);
            boolean cached = false;

            // Check cache first (only for non-Chartink universes)
            if (!"CHARTINK".equalsIgnoreCase(universe)) {
                Optional<BacktestCache> cachedResult = backtestCacheRepository.findByCacheKey(cacheKey);
                if (cachedResult.isPresent()) {
                    log.info("Returning cached backtest result for key={}", cacheKey);
                    BacktestCache cache = cachedResult.get();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = objectMapper.readValue(cache.getResultJson(), LinkedHashMap.class);
                    result.put("cached", true);
                    result.put("cachedAt", cache.getCreatedAt().toString());
                    return ResponseEntity.ok(result);
                }
            }

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

            StrategyPlugin plugin = findPlugin(pluginType);
            StrategyParams params = StrategyParams.defaults();

            log.info("Backtesting plugin={} on {} symbols from {}", pluginType, symbolList.size(), universe);
            // Fetch candles in parallel — max 4 concurrent Zerodha API calls to stay within rate limits
            Map<String, List<CandleData>> candlesBySymbol = new ConcurrentHashMap<>();
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (String symbol : symbolList) {
                    futures.add(pool.submit(() -> {
                        List<CandleData> candles = candleFetchService.fetchCandles(symbol, timeframe, startTime, endTime);
                        if (!candles.isEmpty()) candlesBySymbol.put(symbol, candles);
                    }));
                }
                for (Future<?> f : futures) {
                    try { f.get(240, TimeUnit.SECONDS); } catch (Exception ignored) {}
                }
            } finally {
                pool.shutdown();
            }

            double perTradeCost = brokerage;
            List<SimulatedTrade> allTrades = new ArrayList<>();
            for (Map.Entry<String, List<CandleData>> entry : candlesBySymbol.entrySet()) {
                allTrades.addAll(simulateStrategy(entry.getKey(), entry.getValue(), plugin, params, perTradeCost));
            }
            allTrades.sort(java.util.Comparator.comparing(t -> t.entryTime));

            int totalTrades = allTrades.size();
            int winCount = 0, lossCount = 0;
            double totalPnl = 0, totalBrokerage = 0;
            for (SimulatedTrade t : allTrades) {
                if (t.pnl > 0) winCount++;
                else if (t.pnl < 0) lossCount++;
                totalPnl += t.pnl;
                totalBrokerage += t.brokerage;
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
            result.put("cached", false);
            result.put("cacheKey", cacheKey);
            result.put("strategy", strategyName);
            result.put("universe", universe);
            result.put("chartinkSymbols", useChartinkFilter ? symbolList.size() : 0);
            result.put("chartinkConfigured", chartinkScannerService.isConfigured());
            result.put("symbolsLoaded", candlesBySymbol.size());
            result.put("totalTrades", totalTrades);
            result.put("winCount", winCount);
            result.put("lossCount", lossCount);
            double netPnl = totalPnl - totalBrokerage;
            result.put("totalPnL",       Math.round(totalPnl * 100.0) / 100.0);
            result.put("totalBrokerage", Math.round(totalBrokerage * 100.0) / 100.0);
            result.put("netPnL",         Math.round(netPnl * 100.0) / 100.0);
            result.put("brokeragePerTrade", perTradeCost);
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

            // Save to cache (non-Chartink only)
            if (!"CHARTINK".equalsIgnoreCase(universe)) {
                try {
                    String json = objectMapper.writeValueAsString(result);
                    BacktestCache cache = new BacktestCache();
                    cache.setCacheKey(cacheKey);
                    cache.setStrategyType(strategyName);
                    cache.setUniverse(universe);
                    cache.setDateStart(startTime);
                    cache.setDateEnd(endTime);
                    cache.setBrokerage(brokerage);
                    cache.setResultJson(json);
                    cache.setCreatedAt(LocalDateTime.now());
                    backtestCacheRepository.save(cache);
                } catch (Exception e) {
                    log.warn("Failed to cache backtest result: {}", e.getMessage());
                }
            }

            log.info("Backtest complete: strategy={} trades={} winRate={}% totalPnL={}",
                strategyName, totalTrades, Math.round(winRate * 10.0) / 10.0, Math.round(totalPnl));
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Advanced backtest failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Backtest failed: " + e.getMessage()));
        }
    }

    /**
     * Pairs / Statistical Arbitrage backtest — daily-anchor spread method.
     * pairs:      comma-separated "HDFCBANK:ICICIBANK,TCS:INFY" — uses defaults if blank.
     * entryPct:  % deviation from daily opening ratio to enter (default 1.5%)
     * exitPct:   % reversion back toward anchor to exit (default 0.2%)
     * stopPct:   % stop loss on spread widening (default 3.0%)
     * brokerage: ₹ per pair trade (4 legs × ₹20 = ₹80)
     */
    @PostMapping("/pairs")
    public ResponseEntity<Map<String, Object>> runPairsBacktest(
            @RequestParam(required = false) String pairs,
            @RequestParam(required = false) String dateStart,
            @RequestParam(required = false) String dateEnd,
            @RequestParam(defaultValue = "60")  int    zWindow,
            @RequestParam(defaultValue = "1.5") double zEntry,    // entryPct — kept as zEntry for UI compat
            @RequestParam(defaultValue = "0.2") double zExit,     // exitPct
            @RequestParam(defaultValue = "3.0") double zStop,     // stopPct
            @RequestParam(defaultValue = "80")  double brokerage) {

        log.info("Pairs backtest: pairs={}, entryPct={}, exitPct={}, stopPct={}", pairs, zEntry, zExit, zStop);
        try {
            java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");
            LocalDateTime startTime = dateStart != null
                ? LocalDateTime.parse(dateStart.replace("Z","").substring(0, 19))
                : LocalDateTime.now(IST).minusDays(30);
            LocalDateTime endTime = dateEnd != null
                ? LocalDateTime.parse(dateEnd.replace("Z","").substring(0, 19))
                : LocalDateTime.now(IST);

            // Parse custom pairs or use defaults
            List<String[]> pairList;
            if (pairs != null && !pairs.isBlank()) {
                pairList = Arrays.stream(pairs.split(","))
                    .map(p -> p.trim().split(":"))
                    .filter(p -> p.length == 2)
                    .toList();
            } else {
                pairList = PairsTradingService.DEFAULT_PAIRS;
            }

            // Fetch candles for all unique symbols in parallel
            java.util.Set<String> allSymbols = new java.util.LinkedHashSet<>();
            for (String[] pair : pairList) { allSymbols.add(pair[0]); allSymbols.add(pair[1]); }

            Map<String, List<CandleData>> candleCache = new ConcurrentHashMap<>();
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (String sym : allSymbols) {
                    final LocalDateTime s = startTime, e = endTime;
                    futures.add(pool.submit(() -> {
                        List<CandleData> c = candleFetchService.fetchCandles(sym, "1min", s, e);
                        if (!c.isEmpty()) candleCache.put(sym, c);
                    }));
                }
                for (Future<?> f : futures) {
                    try { f.get(240, TimeUnit.SECONDS); } catch (Exception ignored) {}
                }
            } finally { pool.shutdown(); }

            // Run backtest per pair
            List<Map<String, Object>> pairResults = new ArrayList<>();
            List<PairsTradingService.PairsTradeResult> allTrades = new ArrayList<>();
            List<String> skippedPairs = new ArrayList<>();

            for (String[] pair : pairList) {
                String symA = pair[0].trim(), symB = pair[1].trim();
                List<CandleData> ca = candleCache.get(symA);
                List<CandleData> cb = candleCache.get(symB);
                if (ca == null || cb == null || ca.size() < 50 || cb.size() < 50) {
                    log.warn("Skipping pair {}/{}: insufficient candles ({}/{})",
                        symA, symB, ca == null ? 0 : ca.size(), cb == null ? 0 : cb.size());
                    skippedPairs.add(symA + "/" + symB);
                    continue;
                }

                List<PairsTradingService.PairsTradeResult> pTrades =
                    pairsTradingService.backtestPair(symA, symB, ca, cb, zWindow, zEntry, zExit, zStop, brokerage);

                double corr      = pairsTradingService.computeCorrelation(ca, cb);
                int    wins      = (int) pTrades.stream().filter(t -> t.netPnl() > 0).count();
                int    losses    = (int) pTrades.stream().filter(t -> t.netPnl() < 0).count();
                double pairPnl   = pTrades.stream().mapToDouble(PairsTradingService.PairsTradeResult::netPnl).sum();
                double pairWin   = pTrades.isEmpty() ? 0 : (double) wins / pTrades.size() * 100;

                Map<String, Object> pr = new LinkedHashMap<>();
                pr.put("pair",        symA + "/" + symB);
                pr.put("symbolA",     symA);
                pr.put("symbolB",     symB);
                pr.put("correlation", rnd2(corr));
                pr.put("trades",      pTrades.size());
                pr.put("wins",        wins);
                pr.put("losses",      losses);
                pr.put("winRate",     rnd2(pairWin));
                pr.put("totalPnl",    rnd2(pairPnl));
                pr.put("avgPnl",      pTrades.isEmpty() ? 0 : rnd2(pairPnl / pTrades.size()));
                pr.put("tradeList",   pTrades.stream().map(PairsTradingService.PairsTradeResult::toMap).toList());
                pairResults.add(pr);
                allTrades.addAll(pTrades);
            }

            // Aggregate
            int    totalTrades = allTrades.size();
            int    totalWins   = (int) allTrades.stream().filter(t -> t.netPnl() > 0).count();
            int    totalLosses = (int) allTrades.stream().filter(t -> t.netPnl() < 0).count();
            double totalPnl    = allTrades.stream().mapToDouble(PairsTradingService.PairsTradeResult::netPnl).sum();
            double winRate     = totalTrades > 0 ? (double) totalWins / totalTrades * 100 : 0;

            // Max drawdown on cumulative PnL series
            double peak = 0, trough = 0, maxDd = 0, running = 0;
            for (PairsTradingService.PairsTradeResult t : allTrades) {
                running += t.netPnl();
                if (running > peak) peak = running;
                double dd = peak > 0 ? (peak - running) / peak * 100 : 0;
                if (dd > maxDd) maxDd = dd;
            }

            // Profit factor
            double gProfit = 0, gLoss = 0;
            for (PairsTradingService.PairsTradeResult t : allTrades) {
                if (t.netPnl() > 0) gProfit += t.netPnl();
                else gLoss += Math.abs(t.netPnl());
            }
            double pf = gLoss > 0 ? gProfit / gLoss : (gProfit > 0 ? 999 : 0);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("strategy",        "PAIRS_ARB");
            result.put("method",          "DAILY_ANCHOR_SPREAD");
            result.put("pairsCount",      pairResults.size());
            result.put("skippedPairs",    skippedPairs);
            result.put("zWindow",         zWindow);
            result.put("zEntry",          zEntry);   // entryPct%
            result.put("zExit",           zExit);    // exitPct%
            result.put("zStop",           zStop);    // stopPct%
            result.put("entryPct",        zEntry);
            result.put("exitPct",         zExit);
            result.put("stopPct",         zStop);
            result.put("capitalPerLeg",   PairsTradingService.CAPITAL_PER_LEG);
            result.put("capitalTotal",    PairsTradingService.CAPITAL_PER_LEG * 2);
            result.put("totalTrades",     totalTrades);
            result.put("winCount",        totalWins);
            result.put("lossCount",       totalLosses);
            result.put("winRate",         rnd2(winRate));
            result.put("totalPnL",        rnd2(totalPnl));
            result.put("avgPnL",          totalTrades > 0 ? rnd2(totalPnl / totalTrades) : 0);
            result.put("maxDrawdown",     rnd2(maxDd));
            result.put("profitFactor",    rnd2(pf));
            result.put("brokeragePerTrade", brokerage);
            result.put("pairs",           pairResults);
            result.put("dateRange",       Map.of("start", startTime.toString(), "end", endTime.toString()));

            log.info("Pairs backtest done: {} pairs, {} trades, {}% win rate, ₹{} PnL",
                pairResults.size(), totalTrades, String.format("%.1f", winRate), Math.round(totalPnl));
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Pairs backtest failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Pairs backtest failed: " + e.getMessage()));
        }
    }

    private double rnd2(double v) { return Math.round(v * 100.0) / 100.0; }

    // ─── SWING (POSITIONAL) BACKTEST ─────────────────────────────────────────────

    /**
     * Swing backtest on DAILY candles aggregated from 1-min candle data already in DB.
     * Strategy: THREE_DAY_MOMENTUM (and any future daily-bar strategies).
     * Entry at Day-3 close; hold up to 10 trading days; exit on SL, target, or max-hold.
     */
    @PostMapping("/swing")
    public ResponseEntity<Map<String, Object>> runSwingBacktest(
            @RequestParam(defaultValue = "THREE_DAY_MOMENTUM") String strategy,
            @RequestParam(defaultValue = "FO_STOCKS") String universe,
            @RequestParam(required = false) String dateStart,
            @RequestParam(required = false) String dateEnd,
            @RequestParam(defaultValue = "40") int brokerage,
            @RequestParam(defaultValue = "25000") double capital,
            @RequestParam(defaultValue = "false") boolean useDailyApi) {

        java.time.ZoneId IST2 = java.time.ZoneId.of("Asia/Kolkata");
        LocalDateTime startTime = dateStart != null ? parseDateParam(dateStart) : LocalDateTime.now(IST2).minusMonths(3);
        LocalDateTime endTime   = dateEnd   != null ? parseDateParam(dateEnd)   : LocalDateTime.now(IST2);

        log.info("Swing backtest: strategy={} universe={} {} to {} capital={} useDailyApi={}", strategy, universe, startTime, endTime, capital, useDailyApi);

        try {
            StrategyPlugin plugin = findPlugin(strategy);
            StrategyParams params = StrategyParams.defaults();
            List<String> symbols = getSymbolsForUniverse(universe);

            List<SimulatedTrade> allTrades = new ArrayList<>();
            Set<String> tradedSymbolMonth = new HashSet<>();
            // Per-date signal count cap: max 3 swing signals per trading day across all symbols
            Map<java.time.LocalDate, Integer> dailySignalCount = new java.util.HashMap<>();

            // Regime filter disabled — stock-level 50-day EMA in strategy handles trend filtering
            Map<java.time.LocalDate, Boolean> niftyBullish = Collections.emptyMap();

            for (String sym : symbols) {
                List<DailyBar> bars;
                if (useDailyApi) {
                    // Fetch day-interval candles directly from Zerodha (supports multi-year history)
                    List<CandleData> raw = zerodhaCandleService.fetchCandles(sym, "daily", startTime, endTime);
                    if (raw.isEmpty()) continue;
                    bars = toDailyBarsFromDayCandles(raw);
                } else {
                    List<CandleData> raw = candleRepository
                        .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(sym, "1min", startTime, endTime);
                    if (raw.isEmpty()) continue;
                    bars = aggregateToDailyBars(raw);
                }
                if (bars.size() < 55) continue;

                for (int i = 54; i < bars.size(); i++) {
                    java.time.LocalDate signalDate = bars.get(i).date;

                    // Regime filter: skip signals when Nifty is below 50-day EMA (bear market)
                    if (!niftyBullish.getOrDefault(signalDate, true)) continue;

                    // Cap: max 3 swing signals per day (position concentration limit)
                    if (dailySignalCount.getOrDefault(signalDate, 0) >= 3) continue;

                    List<com.stokr.marketdata.Candle> window = new ArrayList<>();
                    for (int k = 0; k <= i; k++) {
                        window.add(bars.get(k).toCandle());
                    }

                    BigDecimal lastClose = BigDecimal.valueOf(bars.get(i).close);
                    MarketContext ctx = new MarketContext(sym, window, lastClose, lastClose,
                        java.util.Collections.emptyMap(), java.util.Collections.emptyMap());
                    Signal sig = plugin.evaluate(ctx, params);
                    if (sig == null) continue;

                    // One trade per symbol per month
                    String monthKey = sym + "_" + signalDate.getYear() + "_" + signalDate.getMonthValue();
                    if (tradedSymbolMonth.contains(monthKey)) continue;
                    tradedSymbolMonth.add(monthKey);

                    dailySignalCount.merge(signalDate, 1, Integer::sum);

                    // Use closeTs (last 1-min candle of day) as entry time — entry at day's close
                    SimulatedTrade trade = simulateSwingExit(sig, bars, i, capital, brokerage);
                    if (trade != null) allTrades.add(trade);
                }
            }

            // Sort trades by entry time
            allTrades.sort(Comparator.comparing(t -> t.entryTime));

            double totalPnl = allTrades.stream().mapToDouble(t -> t.pnl - t.brokerage).sum();
            long wins = allTrades.stream().filter(t -> t.pnl > t.brokerage).count();
            int totalTrades = allTrades.size();
            double winRate = totalTrades > 0 ? 100.0 * wins / totalTrades : 0;
            double maxDd = calculateMaxDrawdownFromTrades(allTrades);
            double pf = calculateProfitFactorFromTrades(allTrades);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("strategy",      strategy);
            result.put("universe",      universe);
            result.put("symbolsLoaded", allTrades.stream().map(t -> t.symbol).distinct().count());
            result.put("totalTrades",   totalTrades);
            result.put("winCount",      (int) wins);
            result.put("lossCount",     totalTrades - (int) wins);
            result.put("netPnL",        rnd2(totalPnl));
            result.put("winRate",       rnd2(winRate));
            result.put("profitFactor",  rnd2(pf));
            result.put("maxDrawdown",   rnd2(maxDd));
            result.put("brokeragePerTrade", brokerage);
            result.put("capitalPerTrade",   capital);
            result.put("dateRange",     Map.of("start", startTime.toString(), "end", endTime.toString()));
            result.put("trades", allTrades.stream().map(SimulatedTrade::toMap).collect(java.util.stream.Collectors.toList()));
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Swing backtest failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Swing backtest failed: " + e.getMessage()));
        }
    }

    /** Aggregate 1-min candles into daily OHLCV bars. Uses last-candle timestamp as closeTs (entry time). */
    private List<DailyBar> aggregateToDailyBars(List<CandleData> candles) {
        Map<java.time.LocalDate, DailyBar> map = new java.util.TreeMap<>();
        for (CandleData c : candles) {
            java.time.LocalDate day = c.getTimestamp().toLocalDate();
            map.merge(day, new DailyBar(day, c.getOpen().doubleValue(), c.getHigh().doubleValue(),
                    c.getLow().doubleValue(), c.getClose().doubleValue(), c.getVolume(),
                    c.getTimestamp(), c.getTimestamp()),
                (existing, newBar) -> {
                    existing.high    = Math.max(existing.high,  newBar.high);
                    existing.low     = Math.min(existing.low,   newBar.low);
                    existing.close   = newBar.close;
                    existing.closeTs = newBar.openTs;  // track last candle timestamp = close time
                    existing.volume  += newBar.volume;
                    return existing;
                });
        }
        return new ArrayList<>(map.values());
    }

    /**
     * Build a date -> bullish map for Nifty 50.
     * Bullish = Nifty close > 50-day EMA on that date.
     * If Nifty data unavailable for a date, defaults to true (no filter applied).
     */
    private Map<java.time.LocalDate, Boolean> buildNiftyRegimeMap(LocalDateTime startTime, LocalDateTime endTime, boolean useDailyApi) {
        try {
            List<DailyBar> niftyBars;
            // Fetch 1 year before startTime so 50-day EMA is fully converged at backtest start
            LocalDateTime niftyStart = startTime.minusDays(365);
            if (useDailyApi) {
                List<CandleData> raw = zerodhaCandleService.fetchCandles("NIFTY_50", "daily", niftyStart, endTime);
                if (raw.isEmpty()) return Collections.emptyMap();
                niftyBars = toDailyBarsFromDayCandles(raw);
            } else {
                List<CandleData> raw = candleRepository
                    .findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc("NIFTY_50", "1min", niftyStart, endTime);
                if (raw.isEmpty()) return Collections.emptyMap();
                niftyBars = aggregateToDailyBars(raw);
            }
            // Momentum filter: Nifty close > Nifty close 20 bars ago (market trending up)
            // No EMA lag — directly confirms the market is moving higher over the past month
            Map<java.time.LocalDate, Boolean> regime = new java.util.HashMap<>();
            int sz = niftyBars.size();
            for (int i = 20; i < sz; i++) {
                boolean bullish = niftyBars.get(i).close > niftyBars.get(i - 20).close;
                regime.put(niftyBars.get(i).date, bullish);
            }
            log.info("Nifty regime map built: {} dates, {} bullish", regime.size(),
                regime.values().stream().filter(b -> b).count());
            return regime;
        } catch (Exception e) {
            log.warn("Failed to build Nifty regime map: {} — no regime filter applied", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Convert Zerodha "day" interval candles (1 candle per trading day) directly to DailyBars. */
    private List<DailyBar> toDailyBarsFromDayCandles(List<CandleData> dayCand) {
        List<DailyBar> bars = new ArrayList<>();
        for (CandleData c : dayCand) {
            java.time.LocalDate d = c.getTimestamp().toLocalDate();
            LocalDateTime openTs  = d.atTime(9, 15);
            LocalDateTime closeTs = d.atTime(15, 29);
            bars.add(new DailyBar(d, c.getOpen().doubleValue(), c.getHigh().doubleValue(),
                    c.getLow().doubleValue(), c.getClose().doubleValue(), c.getVolume(), openTs, closeTs));
        }
        bars.sort(Comparator.comparing(b -> b.date));
        return bars;
    }

    static class DailyBar {
        java.time.LocalDate date;
        double open, high, low, close;
        long volume;
        LocalDateTime openTs;   // first candle of day (bar open)
        LocalDateTime closeTs;  // last candle of day (bar close — use for swing entry time)

        DailyBar(java.time.LocalDate d, double o, double h, double l, double c, long v, LocalDateTime openTs, LocalDateTime closeTs) {
            this.date = d; this.open = o; this.high = h; this.low = l; this.close = c; this.volume = v;
            this.openTs = openTs; this.closeTs = closeTs;
        }

        com.stokr.marketdata.Candle toCandle() {
            return new com.stokr.marketdata.Candle(null, openTs,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),  BigDecimal.valueOf(close), volume);
        }
    }

    /** Simulate swing exit: check subsequent daily bars for SL, target, or 10-day EOD. */
    private SimulatedTrade simulateSwingExit(Signal sig, List<DailyBar> bars, int entryIdx,
                                              double capital, int brokerageCost) {
        SimulatedTrade trade = new SimulatedTrade(sig.symbol(), sig, entryIdx, bars.get(entryIdx).closeTs, brokerageCost);

        double entry = sig.entryPrice().doubleValue();
        double sl    = sig.stopLoss().doubleValue();
        double tgt   = sig.target().doubleValue();

        int maxHold = 7;  // 7 trading days hold max — swing momentum decays quickly
        for (int k = entryIdx + 1; k < bars.size() && k <= entryIdx + maxHold; k++) {
            DailyBar bar = bars.get(k);
            if (bar.low <= sl) {
                trade.exitAtPrice(k, bar.closeTs, "SL_HIT", sig.stopLoss());
                trade.pnl = rnd2((sl - entry) / entry * capital);
                trade.deductBrokerage();
                return trade;
            }
            if (bar.high >= tgt) {
                trade.exitAtPrice(k, bar.closeTs, "TARGET_HIT", sig.target());
                trade.pnl = rnd2((tgt - entry) / entry * capital);
                trade.deductBrokerage();
                return trade;
            }
            // 5-day time-stop: if no profit after 5 days the momentum has stalled — cut it
            if (k == entryIdx + 5 && bar.close <= entry) {
                trade.exitAtPrice(k, bar.closeTs, "TIME_STOP", BigDecimal.valueOf(bar.close));
                trade.pnl = rnd2((bar.close - entry) / entry * capital);
                trade.deductBrokerage();
                return trade;
            }
            if (k == entryIdx + maxHold) {
                trade.exitAtPrice(k, bar.closeTs, "MAX_HOLD_EXIT", BigDecimal.valueOf(bar.close));
                trade.pnl = rnd2((bar.close - entry) / entry * capital);
                trade.deductBrokerage();
                return trade;
            }
        }

        if (entryIdx + 1 < bars.size()) {
            DailyBar last = bars.get(bars.size() - 1);
            trade.exitAtPrice(bars.size() - 1, last.closeTs, "OPEN_AT_EOD", BigDecimal.valueOf(last.close));
            trade.pnl = rnd2((last.close - entry) / entry * capital);
            trade.deductBrokerage();
            return trade;
        }
        return null;
    }

    @PostMapping("/clear-cache")
    public ResponseEntity<Map<String, Object>> clearBacktestCache() {
        long count = backtestCacheRepository.count();
        backtestCacheRepository.deleteAll();
        log.info("Cleared {} cached backtest results", count);
        return ResponseEntity.ok(Map.of("cleared", count));
    }

    private String computeCacheKey(String pluginType, String universe, LocalDateTime startTime, LocalDateTime endTime, int brokerage) {
        try {
            String raw = pluginType + "|" + universe + "|" + startTime.toString() + "|" + endTime.toString() + "|" + brokerage + "|" + (int) CAPITAL;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(rawForFallback(pluginType, universe, startTime, endTime, brokerage));
        }
    }

    private int rawForFallback(String pluginType, String universe, LocalDateTime startTime, LocalDateTime endTime, int brokerage) {
        return (pluginType + "|" + universe + "|" + startTime.toString() + "|" + endTime.toString() + "|" + brokerage).hashCode();
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

    private LocalDateTime parseDateParam(String s) {
        s = s.replace("Z", "");
        if (s.length() <= 10) return LocalDateTime.parse(s + "T00:00:00");
        return LocalDateTime.parse(s.substring(0, 19));
    }

    private String resolvePluginType(String strategy) {
        if (strategy == null || strategy.isEmpty() || "ALL".equalsIgnoreCase(strategy))
            return "MORNING_SURGE_REVERSAL";
        String mapped = STRATEGY_PLUGIN_MAP.get(strategy.toUpperCase());
        // If not in map, try direct match (allows arbitrary plugin types like MOMENTUM_TRAIL)
        return mapped != null ? mapped : strategy.toUpperCase();
    }

    private StrategyPlugin findPlugin(String type) {
        for (StrategyPlugin p : strategyPlugins) {
            if (p.getStrategyType().equals(type)) return p;
        }
        return strategyPlugins.isEmpty() ? null : strategyPlugins.get(0);
    }

    private List<SimulatedTrade> simulateStrategy(String symbol, List<CandleData> candleData, StrategyPlugin plugin, StrategyParams params, double perTradeCost) {
        List<SimulatedTrade> trades = new ArrayList<>();
        int n = candleData.size();
        if (n < 20) return trades;

        List<Candle> candles = candleData.stream().map(this::toCandle).toList();
        List<IndicatorUtils.Indicators> indicators = IndicatorUtils.computeAll(candleData);

        BigDecimal[] dayVwap = computePerDayVwap(candleData);

        BigDecimal[] dayOpenArr       = new BigDecimal[n];
        BigDecimal[] prevDayCloseArr  = new BigDecimal[n];
        BigDecimal[] orbHighArr   = new BigDecimal[n];
        BigDecimal[] orbLowArr    = new BigDecimal[n];
        BigDecimal[] orbRangeArr  = new BigDecimal[n];
        {
            String curDay = null;
            int dayStart = 0;
            BigDecimal thisDayOpen = null;
            BigDecimal thisPrevDayClose = null;
            BigDecimal runOrbHigh = null, runOrbLow = null;
            boolean orbReady = false;

            for (int i = 0; i < n; i++) {
                String d = candleData.get(i).getTimestamp().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString();
                if (!d.equals(curDay)) {
                    // Record previous day's close (last candle before day boundary)
                    if (curDay != null && dayStart > 0) {
                        thisPrevDayClose = candles.get(i - 1).close();
                    }
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

                dayOpenArr[i]      = thisDayOpen;
                prevDayCloseArr[i] = thisPrevDayClose;
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
            extras.put("prevClose",    i > 0 ? candleData.get(i - 1).getClose() : null);
            extras.put("prevDayClose", prevDayCloseArr[i]);
            // VWAP slope: pass vwap from 1, 3, 5 candles ago (same day only) for trend check
            if (i >= 5) {
                String dayI    = candleData.get(i).getTimestamp().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString();
                String dayIminus1 = candleData.get(i - 1).getTimestamp().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString();
                String dayIminus3 = candleData.get(i - 3).getTimestamp().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString();
                String dayIminus5 = candleData.get(i - 5).getTimestamp().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString();
                extras.put("prevVwap1", dayI.equals(dayIminus1) ? dayVwap[i - 1] : null);
                extras.put("prevVwap3", dayI.equals(dayIminus3) ? dayVwap[i - 3] : null);
                extras.put("prevVwap5", dayI.equals(dayIminus5) ? dayVwap[i - 5] : null);
            } else {
                extras.put("prevVwap1", null);
                extras.put("prevVwap3", null);
                extras.put("prevVwap5", null);
            }

            List<Candle> window = candles.subList(0, i + 1);
            MarketContext context = new MarketContext(symbol, window, candles.get(i).close(), dayVwap[i], indMap, extras);
            Signal signal = plugin != null ? plugin.evaluate(context, params) : null;

            // Price filter: only trade stocks between ₹100 and ₹3000
            if (signal != null && signal.isValid()) {
                double px = signal.entryPrice().doubleValue();
                if (px < 100.0 || px > 3000.0) signal = null;
            }

            if (signal != null && signal.isValid()) {
                tradedDays.add(istDate);
                SimulatedTrade trade = new SimulatedTrade(symbol, signal, i, candles.get(i).timestamp(), perTradeCost);
                java.time.LocalDate entryDate = candleData.get(i).getTimestamp().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();

                BigDecimal currentSL  = signal.stopLoss();
                BigDecimal bestPrice  = signal.entryPrice();
                double entryD = signal.entryPrice().doubleValue();
                boolean trailActivated = false;
                double trailTrigger  = signal.trailTriggerPct();
                double trailDistance = signal.trailDistancePct() / 100.0;

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
                                if (gain >= trailTrigger) trailActivated = true;
                            }
                            if (trailActivated) {
                                BigDecimal newTrail = bestPrice.multiply(BigDecimal.valueOf(1.0 - trailDistance))
                                    .setScale(2, java.math.RoundingMode.HALF_UP);
                                if (newTrail.compareTo(currentSL) > 0) currentSL = newTrail;
                            }
                        } else {
                            if (c.low().compareTo(bestPrice) < 0) {
                                bestPrice = c.low();
                                double gain = (entryD - bestPrice.doubleValue()) / entryD * 100;
                                if (gain >= trailTrigger) trailActivated = true;
                            }
                            if (trailActivated) {
                                BigDecimal newTrail = bestPrice.multiply(BigDecimal.valueOf(1.0 + trailDistance))
                                    .setScale(2, java.math.RoundingMode.HALF_UP);
                                if (newTrail.compareTo(currentSL) < 0) currentSL = newTrail;
                            }
                        }

                        if (signal.side() == Signal.Side.BUY) {
                            if (c.high().compareTo(signal.target()) >= 0) {
                                trade.exit(j, c.timestamp(), "TARGET_HIT");
                                exited = true;
                            } else if (c.low().compareTo(currentSL) <= 0) {
                                String exitLabel = trailActivated ? "TRAIL_SL" : "SL_HIT";
                                trade.exitAtPrice(j, c.timestamp(), exitLabel, currentSL);
                                exited = true;
                            }
                        } else {
                            if (c.low().compareTo(signal.target()) <= 0) {
                                trade.exit(j, c.timestamp(), "TARGET_HIT");
                                exited = true;
                            } else if (c.high().compareTo(currentSL) >= 0) {
                                String exitLabel = trailActivated ? "TRAIL_SL" : "SL_HIT";
                                trade.exitAtPrice(j, c.timestamp(), exitLabel, currentSL);
                                exited = true;
                            }
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

    static double zerodhaIntradayBrokerage(double capital) {
        double brkgPerOrder = Math.min(20, 0.0003 * capital);
        double txNse = 0.0000307 * capital;
        double sebi = 10.0 / 10000000 * capital;
        double stamp = 0.00003 * capital;
        double sttSell = 0.00025 * capital;

        double buyFees = brkgPerOrder + txNse + sebi + stamp;
        double buyGst = 0.18 * (brkgPerOrder + txNse + sebi);

        double sellFees = brkgPerOrder + sttSell + txNse + sebi;
        double sellGst = 0.18 * (brkgPerOrder + txNse + sebi);

        return Math.round((buyFees + buyGst + sellFees + sellGst) * 100.0) / 100.0;
    }

    private static class SimulatedTrade {
        String symbol;
        Signal.Side side;
        BigDecimal entryPrice, stopLoss, target;
        int entryIdx, exitIdx;
        LocalDateTime entryTime, exitTime;
        String exitType;
        double pnl, brokerage, perTradeCost;

        SimulatedTrade(String symbol, Signal signal, int entryIdx, LocalDateTime entryTime, double perTradeCost) {
            this.symbol = symbol;
            this.side = signal.side();
            this.entryPrice = signal.entryPrice();
            this.stopLoss = signal.stopLoss();
            this.target = signal.target();
            this.entryIdx = entryIdx;
            this.entryTime = entryTime;
            this.perTradeCost = perTradeCost;
        }

        void exit(int exitIdx, LocalDateTime exitTime, String exitType) {
            this.exitIdx = exitIdx;
            this.exitTime = exitTime;
            this.exitType = exitType;
            if (entryPrice != null && entryPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal refPrice = "TARGET_HIT".equals(exitType) ? target : stopLoss;
                double movePct = (side == Signal.Side.BUY)
                    ? refPrice.subtract(entryPrice).doubleValue() / entryPrice.doubleValue()
                    : entryPrice.subtract(refPrice).doubleValue() / entryPrice.doubleValue();
                this.pnl = Math.round(movePct * CAPITAL * 100.0) / 100.0;
            }
            deductBrokerage();
        }

        void exitAtPrice(int exitIdx, LocalDateTime exitTime, String exitType, BigDecimal exitPrice) {
            this.exitIdx = exitIdx;
            this.exitTime = exitTime;
            this.exitType = exitType;
            if (entryPrice != null && entryPrice.compareTo(BigDecimal.ZERO) > 0 && exitPrice != null) {
                double movePct;
                if ("TARGET_HIT".equals(exitType)) {
                    movePct = (side == Signal.Side.BUY)
                        ? target.subtract(entryPrice).doubleValue() / entryPrice.doubleValue()
                        : entryPrice.subtract(target).doubleValue() / entryPrice.doubleValue();
                } else if ("SL_HIT".equals(exitType)) {
                    movePct = (side == Signal.Side.BUY)
                        ? -(entryPrice.subtract(stopLoss).doubleValue() / entryPrice.doubleValue())
                        : -(stopLoss.subtract(entryPrice).doubleValue() / entryPrice.doubleValue());
                } else {
                    movePct = (side == Signal.Side.BUY)
                        ? exitPrice.subtract(entryPrice).doubleValue() / entryPrice.doubleValue()
                        : entryPrice.subtract(exitPrice).doubleValue() / entryPrice.doubleValue();
                }
                this.pnl = Math.round(movePct * CAPITAL * 100.0) / 100.0;
            }
            deductBrokerage();
        }

        void deductBrokerage() {
            if (perTradeCost > 0) {
                this.brokerage = perTradeCost;
            } else {
                this.brokerage = zerodhaIntradayBrokerage(CAPITAL);
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
            m.put("brokerage", brokerage);
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
