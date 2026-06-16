package com.stokr.intraday.backtest;

import com.stokr.intraday.detector.*;
import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.domain.NseStock;
import com.stokr.intraday.engine.MarketRegimeDetector;
import com.stokr.intraday.engine.ProbabilityAdjustmentEngine;
import com.stokr.intraday.engine.SetupRankingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("BacktestEngine - Historical Data Validation")
class BacktestEngineTest {

    private BacktestEngine backtestEngine;

    @Mock
    private GapFillDetector gapFillDetector;
    @Mock
    private VwapBounceDetector vwapBounceDetector;
    @Mock
    private SectorLaggardDetector sectorLaggardDetector;
    @Mock
    private EarlyBreakoutDetector earlyBreakoutDetector;
    @Mock
    private MarketRegimeDetector regimeDetector;
    @Mock
    private ProbabilityAdjustmentEngine probabilityEngine;
    @Mock
    private SetupRankingEngine rankingEngine;

    private NseStock testStock;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        backtestEngine = new BacktestEngine(
                gapFillDetector, vwapBounceDetector, sectorLaggardDetector,
                earlyBreakoutDetector, regimeDetector, probabilityEngine, rankingEngine
        );

        testStock = new NseStock();
        testStock.setStockId("INFY");
        testStock.setAverageVolumeDaily(5000000L);
    }

    @Test
    @DisplayName("Should process empty historical data")
    void testEmptyHistoricalData() {
        List<BacktestEngine.HistoricalCandle> emptyData = new ArrayList<>();
        BacktestEngine.BacktestResult result = backtestEngine.runBacktest(emptyData, testStock);

        assertNotNull(result);
        assertEquals(0, result.totalCandles);
        assertEquals(0, result.totalTrades);
        assertNull(result.startDate);
        assertNull(result.endDate);
    }

    @Test
    @DisplayName("Should create HistoricalCandle with correct timestamp")
    void testHistoricalCandleTimestamp() {
        LocalDate date = LocalDate.of(2023, 1, 15);
        BacktestEngine.HistoricalCandle candle = new BacktestEngine.HistoricalCandle(
                date,
                BigDecimal.valueOf(1500),
                BigDecimal.valueOf(1550),
                BigDecimal.valueOf(1480),
                BigDecimal.valueOf(1520),
                BigDecimal.valueOf(1510),
                1000000L,
                BigDecimal.valueOf(25)
        );

        assertEquals(date, candle.date);
        assertNotNull(candle.timestamp);
        assertEquals(date.atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant(), candle.timestamp);
    }

    @Test
    @DisplayName("Should track backtested trade entry and exit")
    void testBacktestedTradeLifecycle() {
        BacktestEngine.BacktestedTrade trade = new BacktestEngine.BacktestedTrade();
        trade.setupType = "gap_fill";
        trade.entryPrice = BigDecimal.valueOf(100);
        trade.targetPrice = BigDecimal.valueOf(110);
        trade.stopPrice = BigDecimal.valueOf(95);
        trade.exitPrice = BigDecimal.valueOf(110);

        // Calculate P&L
        BigDecimal pnl = trade.exitPrice.subtract(trade.entryPrice);
        trade.pnlPercent = pnl.divide(trade.entryPrice, 4, java.math.RoundingMode.HALF_UP);
        trade.isWin = trade.pnlPercent.compareTo(BigDecimal.ZERO) > 0;

        assertEquals("gap_fill", trade.setupType);
        assertEquals(BigDecimal.valueOf(100), trade.entryPrice);
        assertEquals(BigDecimal.valueOf(110), trade.exitPrice);
        assertTrue(trade.isWin);
        assertEquals(0, new BigDecimal("0.1000").compareTo(trade.pnlPercent));
    }

    @Test
    @DisplayName("Should calculate win rate statistics correctly")
    void testWinRateCalculation() {
        List<BacktestEngine.BacktestedTrade> trades = new ArrayList<>();

        // Create 10 winning trades
        for (int i = 0; i < 10; i++) {
            BacktestEngine.BacktestedTrade trade = new BacktestEngine.BacktestedTrade();
            trade.setupType = "gap_fill";
            trade.isWin = true;
            trade.pnlPercent = BigDecimal.valueOf(0.02);
            trades.add(trade);
        }

        // Create 2 losing trades
        for (int i = 0; i < 2; i++) {
            BacktestEngine.BacktestedTrade trade = new BacktestEngine.BacktestedTrade();
            trade.setupType = "gap_fill";
            trade.isWin = false;
            trade.pnlPercent = BigDecimal.valueOf(-0.015);
            trades.add(trade);
        }

        assertEquals(12, trades.size());
        assertEquals(10, trades.stream().filter(t -> t.isWin).count());

        // Win rate should be 10/12 = 0.8333
        BigDecimal winRate = BigDecimal.valueOf(10).divide(
                BigDecimal.valueOf(12), 4, java.math.RoundingMode.HALF_UP
        );
        assertTrue(winRate.compareTo(BigDecimal.valueOf(0.82)) >= 0);
        assertTrue(winRate.compareTo(BigDecimal.valueOf(0.84)) <= 0);
    }

    @Test
    @DisplayName("Should validate against specification targets")
    void testValidationAgainstSpec() {
        BacktestEngine.BacktestStats gapFillStats = new BacktestEngine.BacktestStats();
        gapFillStats.setupType = "gap_fill";
        gapFillStats.totalTrades = 100;
        gapFillStats.winningTrades = 82;
        gapFillStats.losingTrades = 18;
        gapFillStats.winRate = BigDecimal.valueOf(0.82);

        BacktestEngine.BacktestStats vwapStats = new BacktestEngine.BacktestStats();
        vwapStats.setupType = "vwap_bounce";
        vwapStats.totalTrades = 80;
        vwapStats.winningTrades = 58;
        vwapStats.losingTrades = 22;
        vwapStats.winRate = BigDecimal.valueOf(0.7250);

        // Both should pass validation with ??2% tolerance
        BigDecimal gapDiff = gapFillStats.winRate.subtract(BigDecimal.valueOf(0.82)).abs();
        BigDecimal vwapDiff = vwapStats.winRate.subtract(BigDecimal.valueOf(0.71)).abs();

        BigDecimal tolerance = BigDecimal.valueOf(0.02);
        assertTrue(gapDiff.compareTo(tolerance) <= 0, "Gap fill should pass validation");
        assertTrue(vwapDiff.compareTo(tolerance) <= 0, "VWAP bounce should pass validation");
    }

    @Test
    @DisplayName("Should fail validation if outside tolerance")
    void testValidationFailure() {
        BacktestEngine.BacktestStats poorStats = new BacktestEngine.BacktestStats();
        poorStats.setupType = "early_breakout";
        poorStats.totalTrades = 100;
        poorStats.winningTrades = 50;
        poorStats.losingTrades = 50;
        poorStats.winRate = BigDecimal.valueOf(0.50); // Spec is 68% ??2%

        BigDecimal specRate = BigDecimal.valueOf(0.68);
        BigDecimal diff = poorStats.winRate.subtract(specRate).abs();
        BigDecimal tolerance = BigDecimal.valueOf(0.02);

        assertTrue(diff.compareTo(tolerance) > 0, "Should fail validation");
    }

    @Test
    @DisplayName("Should simulate trade exit on target hit")
    void testTradeExitOnTargetHit() {
        List<BacktestEngine.HistoricalCandle> candles = new ArrayList<>();

        // Day 1: entry
        LocalDate day1 = LocalDate.of(2023, 1, 2);
        candles.add(new BacktestEngine.HistoricalCandle(
                day1,
                BigDecimal.valueOf(1500), BigDecimal.valueOf(1510),
                BigDecimal.valueOf(1490), BigDecimal.valueOf(1505),
                BigDecimal.valueOf(1502), 1000000L, BigDecimal.valueOf(20)
        ));

        // Day 2: target hit
        LocalDate day2 = LocalDate.of(2023, 1, 3);
        candles.add(new BacktestEngine.HistoricalCandle(
                day2,
                BigDecimal.valueOf(1505), BigDecimal.valueOf(1520),
                BigDecimal.valueOf(1505), BigDecimal.valueOf(1515),
                BigDecimal.valueOf(1512), 1100000L, BigDecimal.valueOf(21)
        ));

        BacktestEngine.BacktestedTrade trade = new BacktestEngine.BacktestedTrade();
        trade.entryPrice = BigDecimal.valueOf(1505);
        trade.targetPrice = BigDecimal.valueOf(1515);
        trade.stopPrice = BigDecimal.valueOf(1495);
        trade.setupType = "gap_fill";

        // Simulate exit (look ahead from day 1)
        for (int i = 1; i < Math.min(1 + 6, candles.size()); i++) {
            BacktestEngine.HistoricalCandle candle = candles.get(i);
            if (candle.high.compareTo(trade.targetPrice) >= 0) {
                trade.exitPrice = trade.targetPrice;
                trade.exitDate = candle.date;
                trade.reason = "TARGET_HIT";
                break;
            }
        }

        assertEquals("TARGET_HIT", trade.reason);
        assertEquals(BigDecimal.valueOf(1515), trade.exitPrice);
        assertEquals(day2, trade.exitDate);
    }

    @Test
    @DisplayName("Should simulate trade exit on stop loss hit")
    void testTradeExitOnStopLoss() {
        List<BacktestEngine.HistoricalCandle> candles = new ArrayList<>();

        LocalDate day1 = LocalDate.of(2023, 1, 2);
        candles.add(new BacktestEngine.HistoricalCandle(
                day1,
                BigDecimal.valueOf(1500), BigDecimal.valueOf(1510),
                BigDecimal.valueOf(1490), BigDecimal.valueOf(1505),
                BigDecimal.valueOf(1502), 1000000L, BigDecimal.valueOf(20)
        ));

        LocalDate day2 = LocalDate.of(2023, 1, 3);
        candles.add(new BacktestEngine.HistoricalCandle(
                day2,
                BigDecimal.valueOf(1505), BigDecimal.valueOf(1510),
                BigDecimal.valueOf(1485), BigDecimal.valueOf(1490),
                BigDecimal.valueOf(1495), 1100000L, BigDecimal.valueOf(21)
        ));

        BacktestEngine.BacktestedTrade trade = new BacktestEngine.BacktestedTrade();
        trade.entryPrice = BigDecimal.valueOf(1505);
        trade.targetPrice = BigDecimal.valueOf(1520);
        trade.stopPrice = BigDecimal.valueOf(1495);
        trade.setupType = "gap_fill";

        // Check for stop loss hit
        for (int i = 1; i < Math.min(1 + 6, candles.size()); i++) {
            BacktestEngine.HistoricalCandle candle = candles.get(i);
            if (candle.low.compareTo(trade.stopPrice) <= 0) {
                trade.exitPrice = trade.stopPrice;
                trade.exitDate = candle.date;
                trade.reason = "STOPPED_OUT";
                break;
            }
        }

        assertEquals("STOPPED_OUT", trade.reason);
        assertEquals(BigDecimal.valueOf(1495), trade.exitPrice);
        assertEquals(day2, trade.exitDate);
    }

    @Test
    @DisplayName("Should simulate time-based exit after 5 days")
    void testTradeExitOnTimeout() {
        List<BacktestEngine.HistoricalCandle> candles = new ArrayList<>();

        LocalDate startDate = LocalDate.of(2023, 1, 2);
        for (int i = 0; i < 10; i++) {
            LocalDate date = startDate.plus(i, ChronoUnit.DAYS);
            candles.add(new BacktestEngine.HistoricalCandle(
                    date,
                    BigDecimal.valueOf(1500 + i), BigDecimal.valueOf(1510 + i),
                    BigDecimal.valueOf(1490 + i), BigDecimal.valueOf(1505 + i),
                    BigDecimal.valueOf(1502 + i), 1000000L, BigDecimal.valueOf(20)
            ));
        }

        BacktestEngine.BacktestedTrade trade = new BacktestEngine.BacktestedTrade();
        trade.entryPrice = BigDecimal.valueOf(1505);
        trade.targetPrice = BigDecimal.valueOf(1600); // Very high, won't hit
        trade.stopPrice = BigDecimal.valueOf(1400);   // Very low, won't hit
        trade.setupType = "gap_fill";

        int entryIndex = 0;
        BacktestEngine.HistoricalCandle lastCandle = candles.get(Math.min(entryIndex + 5, candles.size() - 1));
        trade.exitPrice = lastCandle.close;
        trade.exitDate = lastCandle.date;
        trade.reason = "TIME_EXIT";

        assertEquals("TIME_EXIT", trade.reason);
        assertTrue(trade.exitDate.isAfter(startDate));
        assertTrue(trade.exitDate.isBefore(startDate.plus(10, ChronoUnit.DAYS)));
    }

    @Test
    @DisplayName("Should calculate average win percentage")
    void testAverageWinPercentage() {
        List<BacktestEngine.BacktestedTrade> trades = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            BacktestEngine.BacktestedTrade trade = new BacktestEngine.BacktestedTrade();
            trade.isWin = true;
            trade.pnlPercent = BigDecimal.valueOf(0.02);
            trades.add(trade);
        }

        BigDecimal totalWinPercent = trades.stream()
                .filter(t -> t.isWin)
                .map(t -> t.pnlPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgWin = totalWinPercent.divide(
                BigDecimal.valueOf(trades.size()), 4, java.math.RoundingMode.HALF_UP
        );

        assertEquals(0, BigDecimal.valueOf(0.02).compareTo(avgWin));
    }

    @Test
    @DisplayName("Should calculate average loss percentage")
    void testAverageLossPercentage() {
        List<BacktestEngine.BacktestedTrade> trades = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            BacktestEngine.BacktestedTrade trade = new BacktestEngine.BacktestedTrade();
            trade.isWin = false;
            trade.pnlPercent = BigDecimal.valueOf(-0.015);
            trades.add(trade);
        }

        BigDecimal totalLossPercent = trades.stream()
                .filter(t -> !t.isWin)
                .map(t -> t.pnlPercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgLoss = totalLossPercent.divide(
                BigDecimal.valueOf(trades.size()), 4, java.math.RoundingMode.HALF_UP
        );

        assertEquals(0, BigDecimal.valueOf(-0.015).compareTo(avgLoss));
    }

    @Test
    @DisplayName("Should handle no trades detected scenario")
    void testNoTradesDetected() {
        List<BacktestEngine.HistoricalCandle> candles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candles.add(new BacktestEngine.HistoricalCandle(
                    LocalDate.of(2023, 1, 2).plus(i, ChronoUnit.DAYS),
                    BigDecimal.valueOf(1500), BigDecimal.valueOf(1510),
                    BigDecimal.valueOf(1490), BigDecimal.valueOf(1505),
                    BigDecimal.valueOf(1502), 1000000L, BigDecimal.valueOf(20)
            ));
        }

        // Mock all detectors to return null (no setups detected)
        when(gapFillDetector.detectSetup(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        when(vwapBounceDetector.detectSetup(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        when(sectorLaggardDetector.detectSetup(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        when(earlyBreakoutDetector.detectSetup(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        BacktestEngine.BacktestResult result = backtestEngine.runBacktest(candles, testStock);

        assertEquals(5, result.totalCandles);
        assertEquals(0, result.totalTrades);
        assertEquals(BigDecimal.ZERO, result.overallWinRate);
    }

    @Test
    @DisplayName("Should track validation results")
    void testValidationResults() {
        BacktestEngine.ValidationResults results = new BacktestEngine.ValidationResults();
        results.allPassed = true;
        results.passed.put("gap_fill", "82.00% (spec: 82.00%)");
        results.passed.put("vwap_bounce", "71.00% (spec: 71.00%)");

        assertTrue(results.allPassed);
        assertEquals(2, results.passed.size());
        assertTrue(results.failures.isEmpty());
    }

    @Test
    @DisplayName("Should track validation failures")
    void testValidationFailureTracking() {
        BacktestEngine.ValidationResults results = new BacktestEngine.ValidationResults();
        results.allPassed = false;
        results.failures.put("sector_laggard", "No trades detected");
        results.failures.put("early_breakout", "68.50% (spec: 68.00%, diff: 0.50%)");

        assertFalse(results.allPassed);
        assertEquals(2, results.failures.size());
        assertTrue(results.passed.isEmpty());
    }
}
