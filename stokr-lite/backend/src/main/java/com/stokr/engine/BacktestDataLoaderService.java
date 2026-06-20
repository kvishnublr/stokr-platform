package com.stokr.engine;

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

    // Strategy to symbols mapping (hardcoded for now, can be made dynamic)
    private static final Map<String, List<String>> STRATEGY_SYMBOLS = Map.ofEntries(
        Map.entry("ORB", List.of("RELIANCE", "TCS", "INFY", "HDFC", "ICICI")),
        Map.entry("ADV_CASH", List.of("RELIANCE", "TCS", "WIPRO", "AXISBANK", "INFY")),
        Map.entry("VWAP_SQUEEZE", List.of("RELIANCE", "TCS", "WIPRO", "HDFC", "ICICI")),
        Map.entry("GAP_FILL", List.of("RELIANCE", "TCS", "INFY", "HDFC", "AXISBANK")),
        Map.entry("VWAP_BOUNCE", List.of("RELIANCE", "TCS", "WIPRO", "ICICI", "INFY"))
    );

    private static final List<String> DEFAULT_SYMBOLS = List.of("RELIANCE", "TCS", "WIPRO", "INFY", "HDFC");

    public List<String> getStrategySymbols(String strategy) {
        if (strategy == null || "ALL".equalsIgnoreCase(strategy)) {
            return DEFAULT_SYMBOLS;
        }
        return STRATEGY_SYMBOLS.getOrDefault(strategy.toUpperCase(), DEFAULT_SYMBOLS);
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
