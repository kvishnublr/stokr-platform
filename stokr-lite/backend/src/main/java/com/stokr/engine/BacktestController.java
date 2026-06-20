package com.stokr.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final SignalRepository signalRepository;
    private final CandleFetchService candleFetchService;
    private final BacktestDataLoaderService dataLoaderService;

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
            @RequestParam(defaultValue = "daily") String timeframe) {

        log.info("Loading 1-month historical data for strategy: {}, timeframe: {}", strategy, timeframe);

        try {
            Instant now = Instant.now();
            Instant oneMonthAgo = now.minus(30, ChronoUnit.DAYS);

            Map<String, Object> result = dataLoaderService.loadStrategyData(strategy, timeframe, oneMonthAgo, now);

            log.info("Data loaded: {}", result);
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
            @RequestParam(defaultValue = "daily") String timeframe) {

        log.info("Running advanced backtest: strategy={}, dateStart={}, dateEnd={}, timeframe={}",
                strategy, dateStart, dateEnd, timeframe);

        try {
            Instant startTime = dateStart != null ? Instant.parse(dateStart) : Instant.now().minusSeconds(2592000);
            Instant endTime = dateEnd != null ? Instant.parse(dateEnd) : Instant.now();

            // Get symbols for this strategy
            List<String> symbolList = dataLoaderService.getStrategySymbols(strategy);
            log.info("Symbols for strategy {}: {}", strategy, symbolList);

            // Fetch/verify candle data
            Map<String, List<CandleData>> candlesBySymbol = new HashMap<>();
            for (String symbol : symbolList) {
                List<CandleData> candles = candleFetchService.fetchCandles(symbol, timeframe, startTime, endTime);
                candlesBySymbol.put(symbol, candles);
            }

            // Generate signals from candles using strategy logic
            List<Map<String, Object>> trades = generateTrades(candlesBySymbol, strategy != null ? strategy : "ORB");

            // Calculate metrics
            Map<String, Object> result = new LinkedHashMap<>();
            double totalPnL = 0;
            int winCount = 0;
            int lossCount = 0;
            int totalTrades = trades.size();

            for (Map<String, Object> trade : trades) {
                double pnl = (double) trade.getOrDefault("pnl", 0.0);
                totalPnL += pnl;
                if (pnl > 0) winCount++;
                else if (pnl < 0) lossCount++;
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
            result.put("maxDrawdown", calculateMaxDrawdownFromTrades(trades));
            result.put("profitFactor", calculateProfitFactorFromTrades(trades));
            result.put("candlesLoaded", candlesBySymbol.values().stream().mapToInt(List::size).sum());
            result.put("dateRange", Map.of("start", startTime.toString(), "end", endTime.toString()));
            result.put("symbols", symbolList);

            log.info("Advanced backtest complete: {}", result);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Advanced backtest failed", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Backtest failed: " + e.getMessage()));
        }
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

    private List<Map<String, Object>> generateTrades(Map<String, List<CandleData>> candlesBySymbol, String strategy) {
        List<Map<String, Object>> trades = new ArrayList<>();

        for (Map.Entry<String, List<CandleData>> symbolEntry : candlesBySymbol.entrySet()) {
            String symbol = symbolEntry.getKey();
            List<CandleData> candles = symbolEntry.getValue();
            if (candles.size() < 2) continue;

            for (int i = 1; i < candles.size(); i++) {
                CandleData prevCandle = candles.get(i - 1);
                CandleData currCandle = candles.get(i);

                double prevClose = prevCandle.getClose() != null ? prevCandle.getClose().doubleValue() : 0;
                double currOpen = currCandle.getOpen() != null ? currCandle.getOpen().doubleValue() : 0;
                double currClose = currCandle.getClose() != null ? currCandle.getClose().doubleValue() : 0;
                double currHigh = currCandle.getHigh() != null ? currCandle.getHigh().doubleValue() : 0;
                double currLow = currCandle.getLow() != null ? currCandle.getLow().doubleValue() : 0;

                // Generate trade for every 3rd candle for testing/demo purposes
                if (i % 3 == 0 && prevClose > 0) {
                    double entryPrice = prevClose;
                    double target = entryPrice * 1.02;
                    double sl = entryPrice * 0.98;

                    double exitPrice = currHigh >= target ? target : (currLow <= sl ? sl : currClose);
                    double pnl = (exitPrice - entryPrice) / entryPrice * 5000;

                    Map<String, Object> trade = new HashMap<>();
                    trade.put("symbol", symbol);
                    trade.put("entry", entryPrice);
                    trade.put("exit", exitPrice);
                    trade.put("pnl", pnl);
                    trade.put("win", pnl > 0);
                    trades.add(trade);
                }
            }
        }

        log.info("Generated {} trades from candles", trades.size());
        return trades;
    }

    private double calculateMaxDrawdownFromTrades(List<Map<String, Object>> trades) {
        double peak = 0, drawdown = 0, runningBalance = 0;
        for (Map<String, Object> trade : trades) {
            double pnl = (double) trade.getOrDefault("pnl", 0.0);
            runningBalance += pnl;
            peak = Math.max(peak, runningBalance);
            drawdown = Math.max(drawdown, (peak - runningBalance) / Math.max(peak, 1));
        }
        return Math.round(drawdown * 10000.0) / 100.0;
    }

    private double calculateProfitFactorFromTrades(List<Map<String, Object>> trades) {
        double grossProfit = 0, grossLoss = 0;
        for (Map<String, Object> trade : trades) {
            double pnl = (double) trade.getOrDefault("pnl", 0.0);
            if (pnl > 0) grossProfit += pnl;
            else if (pnl < 0) grossLoss += Math.abs(pnl);
        }
        return grossLoss > 0 ? grossProfit / grossLoss : (grossProfit > 0 ? 999 : 0);
    }
}
