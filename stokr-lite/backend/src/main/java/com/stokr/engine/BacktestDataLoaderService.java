package com.stokr.engine;

import com.stokr.strategy.UniverseGroupService;
import com.stokr.strategy.UniverseSymbol;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestDataLoaderService {

    private final CandleFetchService candleFetchService;
    private final SignalRepository signalRepository;
    private final UniverseGroupService universeGroupService;

    // Strategy to universe mapping
    private static final Map<String, String> STRATEGY_UNIVERSES = Map.ofEntries(
        Map.entry("ORB", "NIFTY_50"),
        Map.entry("ADV_CASH", "NIFTY_100"),
        Map.entry("VWAP_SQUEEZE", "NIFTY_50"),
        Map.entry("GAP_FILL", "NIFTY_100"),
        Map.entry("VWAP_BOUNCE", "NIFTY_50")
    );

    private static final List<String> DEFAULT_SYMBOLS = List.of("RELIANCE", "TCS", "WIPRO", "INFY", "HDFC", "ICICI", "AXISBANK");

    public List<String> getStrategySymbols(String strategy) {
        try {
            // Get universe key from strategy
            String universeKey = STRATEGY_UNIVERSES.getOrDefault(
                strategy != null ? strategy.toUpperCase() : "ALL",
                "NIFTY_50"
            );

            // Load symbols from database universe group
            var group = universeGroupService.findByKey(universeKey);
            if (group.isPresent()) {
                List<UniverseSymbol> symbols = universeGroupService.getSymbols(group.get().getId());
                List<String> symbolNames = symbols.stream()
                    .map(UniverseSymbol::getSymbol)
                    .toList();
                log.info("Loaded {} symbols from universe {}", symbolNames.size(), universeKey);
                return symbolNames;
            }
        } catch (Exception e) {
            log.warn("Failed to load symbols from universe, using defaults: {}", e.getMessage());
        }

        // Fallback to defaults
        return DEFAULT_SYMBOLS;
    }

    public Map<String, Object> loadStrategyData(String strategy, String timeframe, Instant startTime, Instant endTime) {
        List<String> symbols = getStrategySymbols(strategy);
        log.info("Loading data for strategy: {}, symbols: {}, timeframe: {}", strategy, symbols, timeframe);

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Integer> loadedCounts = new HashMap<>();
        List<String> failedSymbols = new ArrayList<>();

        for (String symbol : symbols) {
            try {
                // Check if data already exists
                List<CandleData> existingCandles = candleFetchService.fetchCandles(symbol, timeframe, startTime, endTime);

                if (existingCandles.isEmpty()) {
                    log.info("No existing data for {}, fetching from Chartink...", symbol);
                    List<CandleData> candles = candleFetchService.fetchCandlesFromChartink(symbol, timeframe, startTime, endTime);

                    if (candles.isEmpty()) {
                        log.warn("No data from Chartink for {}, trying Zerodha...", symbol);
                        candles = candleFetchService.fetchCandlesFromZerodha(symbol, timeframe, startTime, endTime);
                    }

                    if (!candles.isEmpty()) {
                        candleFetchService.saveCandles(candles);
                        loadedCounts.put(symbol, candles.size());
                        log.info("Loaded {} candles for {}", candles.size(), symbol);
                    } else {
                        log.warn("Failed to load any candles for {}", symbol);
                        failedSymbols.add(symbol);
                        loadedCounts.put(symbol, 0);
                    }
                } else {
                    loadedCounts.put(symbol, existingCandles.size());
                    log.info("Using existing {} candles for {}", existingCandles.size(), symbol);
                }

            } catch (Exception e) {
                log.error("Error loading data for {}: {}", symbol, e.getMessage());
                failedSymbols.add(symbol);
                loadedCounts.put(symbol, 0);
            }
        }

        result.put("strategy", strategy != null ? strategy : "ALL");
        result.put("timeframe", timeframe);
        result.put("symbols", symbols);
        result.put("loadedCounts", loadedCounts);
        result.put("totalCandles", loadedCounts.values().stream().mapToInt(Integer::intValue).sum());
        result.put("failedSymbols", failedSymbols);
        result.put("dateRange", Map.of(
            "start", startTime.toString(),
            "end", endTime.toString()
        ));
        result.put("status", failedSymbols.isEmpty() ? "SUCCESS" : "PARTIAL");

        return result;
    }
}
