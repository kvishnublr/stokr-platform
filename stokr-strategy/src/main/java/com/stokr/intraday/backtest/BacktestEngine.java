package com.stokr.intraday.backtest;

import com.stokr.intraday.detector.*;
import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.domain.NseStock;
import com.stokr.intraday.engine.MarketRegimeDetector;
import com.stokr.intraday.engine.ProbabilityAdjustmentEngine;
import com.stokr.intraday.engine.SetupRankingEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Backtesting engine for validating setup detectors against historical data
 *
 * Features:
 * - Processes 5 years of NSE historical data (2019-2024)
 * - Simulates real-time setup detection
 * - Tracks trades from entry to exit
 * - Calculates win rates and performance metrics
 * - Validates against specification targets
 */
@Service
@Slf4j
public class BacktestEngine {

    private final GapFillDetector gapFillDetector;
    private final VwapBounceDetector vwapBounceDetector;
    private final SectorLaggardDetector sectorLaggardDetector;
    private final EarlyBreakoutDetector earlyBreakoutDetector;
    private final MarketRegimeDetector regimeDetector;
    private final ProbabilityAdjustmentEngine probabilityEngine;
    private final SetupRankingEngine rankingEngine;

    // Specification targets
    private static final Map<String, BigDecimal> SPEC_WIN_RATES = Map.of(
            "gap_fill", BigDecimal.valueOf(0.82),           // 82%
            "vwap_bounce", BigDecimal.valueOf(0.71),        // 71%
            "sector_laggard", BigDecimal.valueOf(0.73),     // 73%
            "early_breakout", BigDecimal.valueOf(0.68)      // 68%
    );

    private static final BigDecimal ACCURACY_TOLERANCE = BigDecimal.valueOf(0.02); // ??2%

    public BacktestEngine(
            GapFillDetector gapFillDetector,
            VwapBounceDetector vwapBounceDetector,
            SectorLaggardDetector sectorLaggardDetector,
            EarlyBreakoutDetector earlyBreakoutDetector,
            MarketRegimeDetector regimeDetector,
            ProbabilityAdjustmentEngine probabilityEngine,
            SetupRankingEngine rankingEngine) {
        this.gapFillDetector = gapFillDetector;
        this.vwapBounceDetector = vwapBounceDetector;
        this.sectorLaggardDetector = sectorLaggardDetector;
        this.earlyBreakoutDetector = earlyBreakoutDetector;
        this.regimeDetector = regimeDetector;
        this.probabilityEngine = probabilityEngine;
        this.rankingEngine = rankingEngine;
    }

    /**
     * Run backtest on historical data
     *
     * @param historicalData List of historical candles with OHLCV
     * @param stock Stock reference data
     * @return Backtest results with metrics
     */
    public BacktestResult runBacktest(List<HistoricalCandle> historicalData, NseStock stock) {
        BacktestResult result = new BacktestResult();
        result.stockId = stock.getStockId();
        result.startDate = historicalData.isEmpty() ? null : historicalData.get(0).date;
        result.endDate = historicalData.isEmpty() ? null : historicalData.get(historicalData.size() - 1).date;
        result.totalCandles = historicalData.size();

        // Track open trades by setup type
        Map<String, List<BacktestedTrade>> trades = new ConcurrentHashMap<>();
        trades.put("gap_fill", new ArrayList<>());
        trades.put("vwap_bounce", new ArrayList<>());
        trades.put("sector_laggard", new ArrayList<>());
        trades.put("early_breakout", new ArrayList<>());

        // Process each candle
        for (int i = 0; i < historicalData.size(); i++) {
            HistoricalCandle candle = historicalData.get(i);
            Integer hourOfDay = getHourOfDay(candle.timestamp);

            // Detect setups
            List<CurrentSetup> detectedSetups = detectSetups(
                    stock, candle, historicalData.get(Math.max(0, i - 1)), hourOfDay
            );

            // Process detections as trade entries
            for (CurrentSetup setup : detectedSetups) {
                BacktestedTrade trade = new BacktestedTrade();
                trade.setupType = setup.getSetupType();
                trade.entryPrice = candle.close;
                trade.entryDate = candle.date;
                trade.targetPrice = setup.getTargetPrice();
                trade.stopPrice = setup.getStopLoss();
                trade.entryTime = candle.timestamp;

                // Simulate trade exit (look ahead for next 5 days)
                simulateTradeExit(trade, historicalData, i);

                // Calculate result
                if (trade.exitPrice != null) {
                    BigDecimal pnl = trade.exitPrice.subtract(trade.entryPrice);
                    trade.pnlPercent = pnl.divide(trade.entryPrice, 4, RoundingMode.HALF_UP);
                    trade.isWin = trade.pnlPercent.compareTo(BigDecimal.ZERO) > 0;
                }

                trades.get(setup.getSetupType()).add(trade);
            }
        }

        // Calculate statistics by setup type
        for (String setupType : trades.keySet()) {
            List<BacktestedTrade> setupTrades = trades.get(setupType);
            if (!setupTrades.isEmpty()) {
                BacktestStats stats = calculateStats(setupTrades, setupType);
                result.statsBySetupType.put(setupType, stats);
            }
        }

        // Overall statistics
        List<BacktestedTrade> allTrades = trades.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
        result.totalTrades = allTrades.size();
        result.winningTrades = (int) allTrades.stream().filter(t -> t.isWin).count();
        result.losingTrades = result.totalTrades - result.winningTrades;
        result.overallWinRate = result.totalTrades > 0 ?
                BigDecimal.valueOf(result.winningTrades).divide(BigDecimal.valueOf(result.totalTrades), 4, RoundingMode.HALF_UP) :
                BigDecimal.ZERO;

        // Validate against specification
        result.validationResults = validateAgainstSpec(result.statsBySetupType);

        log.info("backtest.complete stock={} total_trades={} win_rate={} validated={}",
                stock.getStockId(), result.totalTrades, result.overallWinRate, result.validationResults.allPassed);

        return result;
    }

    /**
     * Detect setups for a single candle
     */
    private List<CurrentSetup> detectSetups(
            NseStock stock,
            HistoricalCandle current,
            HistoricalCandle previous,
            Integer hourOfDay) {

        List<CurrentSetup> setups = new ArrayList<>();
        Instant timestamp = current.timestamp;

        // Gap fill detection
        CurrentSetup gapFill = gapFillDetector.detectSetup(
                stock, current.close, current.vwap, current.open, previous.close,
                current.volume, stock.getAverageVolumeDaily(), current.atr14, hourOfDay, timestamp
        );
        if (gapFill != null) setups.add(gapFill);

        // VWAP bounce detection
        CurrentSetup vwapBounce = vwapBounceDetector.detectSetup(
                stock, current.close, current.vwap, current.open, previous.close,
                current.volume, stock.getAverageVolumeDaily(), current.atr14, hourOfDay, timestamp
        );
        if (vwapBounce != null) setups.add(vwapBounce);

        // Sector laggard detection
        CurrentSetup sectorLaggard = sectorLaggardDetector.detectSetup(
                stock, current.close, current.vwap, current.open, previous.close,
                current.volume, stock.getAverageVolumeDaily(), current.atr14, hourOfDay, timestamp
        );
        if (sectorLaggard != null) setups.add(sectorLaggard);

        // Early breakout detection
        CurrentSetup earlyBreakout = earlyBreakoutDetector.detectSetup(
                stock, current.close, current.vwap, current.open, previous.close,
                current.volume, stock.getAverageVolumeDaily(), current.atr14, hourOfDay, timestamp
        );
        if (earlyBreakout != null) setups.add(earlyBreakout);

        return setups;
    }

    /**
     * Simulate trade exit by looking ahead for target/stop hit
     */
    private void simulateTradeExit(BacktestedTrade trade, List<HistoricalCandle> candles, int entryIndex) {
        // Look ahead up to 5 days (1 week of trading)
        for (int i = entryIndex + 1; i < Math.min(entryIndex + 6, candles.size()); i++) {
            HistoricalCandle candle = candles.get(i);

            // Check if target hit
            if (candle.high.compareTo(trade.targetPrice) >= 0) {
                trade.exitPrice = trade.targetPrice;
                trade.exitDate = candle.date;
                trade.exitTime = candle.timestamp;
                trade.reason = "TARGET_HIT";
                return;
            }

            // Check if stop hit
            if (candle.low.compareTo(trade.stopPrice) <= 0) {
                trade.exitPrice = trade.stopPrice;
                trade.exitDate = candle.date;
                trade.exitTime = candle.timestamp;
                trade.reason = "STOPPED_OUT";
                return;
            }
        }

        // If no exit in 5 days, close at market
        HistoricalCandle lastCandle = candles.get(Math.min(entryIndex + 5, candles.size() - 1));
        trade.exitPrice = lastCandle.close;
        trade.exitDate = lastCandle.date;
        trade.exitTime = lastCandle.timestamp;
        trade.reason = "TIME_EXIT";
    }

    /**
     * Calculate statistics for a setup type
     */
    private BacktestStats calculateStats(List<BacktestedTrade> trades, String setupType) {
        BacktestStats stats = new BacktestStats();
        stats.setupType = setupType;
        stats.totalTrades = trades.size();
        stats.winningTrades = (int) trades.stream().filter(t -> t.isWin).count();
        stats.losingTrades = stats.totalTrades - stats.winningTrades;
        stats.winRate = BigDecimal.valueOf(stats.winningTrades).divide(
                BigDecimal.valueOf(stats.totalTrades), 4, RoundingMode.HALF_UP);

        // Calculate average win/loss
        BigDecimal totalWinPercent = trades.stream()
                .filter(t -> t.isWin)
                .map(t -> t.pnlPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        stats.avgWinPercent = stats.winningTrades > 0 ?
                totalWinPercent.divide(BigDecimal.valueOf(stats.winningTrades), 4, RoundingMode.HALF_UP) :
                BigDecimal.ZERO;

        BigDecimal totalLossPercent = trades.stream()
                .filter(t -> !t.isWin)
                .map(t -> t.pnlPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        stats.avgLossPercent = stats.losingTrades > 0 ?
                totalLossPercent.divide(BigDecimal.valueOf(stats.losingTrades), 4, RoundingMode.HALF_UP) :
                BigDecimal.ZERO;

        // Total profit
        stats.totalProfit = trades.stream()
                .map(t -> t.pnlPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return stats;
    }

    /**
     * Validate results against specification targets
     */
    private ValidationResults validateAgainstSpec(Map<String, BacktestStats> stats) {
        ValidationResults results = new ValidationResults();
        results.allPassed = true;

        for (String setupType : SPEC_WIN_RATES.keySet()) {
            BigDecimal specWinRate = SPEC_WIN_RATES.get(setupType);
            BacktestStats actual = stats.get(setupType);

            if (actual == null) {
                results.allPassed = false;
                results.failures.put(setupType, "No trades detected");
                continue;
            }

            // Check if within ??2% tolerance
            BigDecimal diff = actual.winRate.subtract(specWinRate).abs();
            if (diff.compareTo(ACCURACY_TOLERANCE) <= 0) {
                results.passed.put(setupType, String.format("%.2f%% (spec: %.2f%%)",
                        actual.winRate.multiply(BigDecimal.valueOf(100)),
                        specWinRate.multiply(BigDecimal.valueOf(100))));
            } else {
                results.allPassed = false;
                results.failures.put(setupType, String.format("%.2f%% (spec: %.2f%%, diff: %.2f%%)",
                        actual.winRate.multiply(BigDecimal.valueOf(100)),
                        specWinRate.multiply(BigDecimal.valueOf(100)),
                        diff.multiply(BigDecimal.valueOf(100))));
            }
        }

        return results;
    }

    /**
     * Get hour of day from timestamp
     */
    private Integer getHourOfDay(Instant timestamp) {
        // Extract hour from instant in IST timezone
        return java.time.ZonedDateTime.ofInstant(timestamp, ZoneId.of("Asia/Kolkata"))
                .getHour();
    }

    /**
     * Historical candle data DTO
     */
    public static class HistoricalCandle {
        public LocalDate date;
        public java.time.Instant timestamp;
        public BigDecimal open;
        public BigDecimal high;
        public BigDecimal low;
        public BigDecimal close;
        public BigDecimal vwap;
        public Long volume;
        public BigDecimal atr14;

        public HistoricalCandle(LocalDate date, BigDecimal open, BigDecimal high,
                               BigDecimal low, BigDecimal close, BigDecimal vwap,
                               Long volume, BigDecimal atr14) {
            this.date = date;
            this.timestamp = date.atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.vwap = vwap;
            this.volume = volume;
            this.atr14 = atr14;
        }
    }

    /**
     * Backtested trade record
     */
    public static class BacktestedTrade {
        public String setupType;
        public LocalDate entryDate;
        public Instant entryTime;
        public BigDecimal entryPrice;
        public BigDecimal targetPrice;
        public BigDecimal stopPrice;
        public LocalDate exitDate;
        public Instant exitTime;
        public BigDecimal exitPrice;
        public BigDecimal pnlPercent;
        public boolean isWin;
        public String reason; // TARGET_HIT, STOPPED_OUT, TIME_EXIT
    }

    /**
     * Statistics for a setup type
     */
    public static class BacktestStats {
        public String setupType;
        public int totalTrades;
        public int winningTrades;
        public int losingTrades;
        public BigDecimal winRate;
        public BigDecimal avgWinPercent;
        public BigDecimal avgLossPercent;
        public BigDecimal totalProfit;
    }

    /**
     * Backtest result containing all metrics and statistics
     */
    public static class BacktestResult {
        public String stockId;
        public LocalDate startDate;
        public LocalDate endDate;
        public int totalCandles;
        public int totalTrades;
        public int winningTrades;
        public int losingTrades;
        public BigDecimal overallWinRate;
        public Map<String, BacktestStats> statsBySetupType = new HashMap<>();
        public ValidationResults validationResults;
    }

    /**
     * Validation results against specification
     */
    public static class ValidationResults {
        public boolean allPassed;
        public Map<String, String> passed = new HashMap<>();
        public Map<String, String> failures = new HashMap<>();
    }
}
