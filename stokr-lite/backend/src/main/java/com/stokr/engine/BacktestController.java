package com.stokr.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final SignalRepository signalRepository;
    private final CandleFetchService candleFetchService;
    private final CandleDataRepository candleRepository;

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

            List<String> symbolList = getSymbolsForStrategy(strategy);

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
                    log.warn("No candles for {}, generating mock data", symbol);
                    candles = candleFetchService.generateMockCandles(symbol, timeframe, startTime, endTime);
                    candleFetchService.saveCandles(candles);
                }
                totalCandles += candles.size();
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
            @RequestParam(defaultValue = "daily") String timeframe) {

        log.info("Running advanced backtest: strategy={}, dateStart={}, dateEnd={}, symbols={}, timeframe={}",
                strategy, dateStart, dateEnd, symbols, timeframe);

        try {
            Instant startTime = dateStart != null ? Instant.parse(dateStart) : Instant.now().minusSeconds(2592000);
            Instant endTime = dateEnd != null ? Instant.parse(dateEnd) : Instant.now();
            List<String> symbolList = symbols != null ? Arrays.asList(symbols.split(",")) : getSymbolsForStrategy(strategy);

            // Fetch candle data
            Map<String, List<CandleData>> candlesBySymbol = new HashMap<>();
            for (String symbol : symbolList) {
                List<CandleData> candles = candleFetchService.fetchCandles(symbol, timeframe, startTime, endTime);
                if (candles.isEmpty()) {
                    log.warn("No candles found for {}, generating mock data", symbol);
                    candles = candleFetchService.generateMockCandles(symbol, timeframe, startTime, endTime);
                    candleFetchService.saveCandles(candles);
                }
                candlesBySymbol.put(symbol, candles);
            }

            // Get signals
            List<SignalEntity> signals = signalRepository.findAll();
            if (strategy != null && !strategy.isEmpty()) {
                signals = signals.stream()
                    .filter(s -> s.getReason() != null && s.getReason().contains(strategy))
                    .toList();
            }

            // Calculate metrics
            Map<String, Object> result = new LinkedHashMap<>();
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

            result.put("strategy", strategy != null ? strategy : "ALL");
            result.put("totalTrades", totalTrades);
            result.put("winCount", winCount);
            result.put("lossCount", lossCount);
            result.put("totalPnL", Math.round(totalPnL * 100.0) / 100.0);
            result.put("winRate", Math.round(winRate * 100.0) / 100.0);
            result.put("avgPnL", Math.round(avgPnL * 100.0) / 100.0);
            result.put("maxDrawdown", calculateMaxDrawdown(signals));
            result.put("profitFactor", calculateProfitFactor(signals));
            result.put("candlesLoaded", candlesBySymbol.values().stream().mapToInt(List::size).sum());
            result.put("dateRange", Map.of("start", startTime.toString(), "end", endTime.toString()));

            log.info("Advanced backtest complete: {}", result);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Advanced backtest failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Backtest failed: " + e.getMessage()));
        }
    }

    private List<String> getSymbolsForStrategy(String strategy) {
        if (strategy == null || strategy.isEmpty() || "ALL".equals(strategy)) {
            return List.of("RELIANCE", "TCS", "WIPRO", "INFY", "HDFC", "ICICI", "AXISBANK");
        }
        return switch (strategy.toUpperCase()) {
            case "ORB" -> List.of("RELIANCE", "TCS", "INFY", "HDFC", "ICICI");
            case "ADV_CASH" -> List.of("RELIANCE", "TCS", "WIPRO", "AXISBANK", "INFY");
            case "VWAP_SQUEEZE" -> List.of("RELIANCE", "TCS", "WIPRO", "HDFC", "ICICI");
            case "GAP_FILL" -> List.of("RELIANCE", "TCS", "INFY", "HDFC", "AXISBANK");
            case "VWAP_BOUNCE" -> List.of("RELIANCE", "TCS", "WIPRO", "ICICI", "INFY");
            default -> List.of("RELIANCE", "TCS", "WIPRO");
        };
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
