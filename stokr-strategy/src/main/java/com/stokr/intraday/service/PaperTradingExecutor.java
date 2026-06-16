package com.stokr.intraday.service;

import com.stokr.intraday.domain.IndexSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * PAPER TRADING EXECUTOR for INDEX HUNT
 * Simulates order execution without real money
 *
 * Features:
 * - Execute signals (buy options)
 * - Monitor for T1/SL hit
 * - Calculate P&L
 * - Track outcomes
 * - Realistic slippage simulation
 */
@Service
@Slf4j
public class PaperTradingExecutor {

    private final IndexHuntService indexHuntService;
    private final Map<Long, PaperTrade> activeTrades = new HashMap<>();

    // Paper trading configuration
    private static final BigDecimal SLIPPAGE_ENTRY_BPS = BigDecimal.valueOf(5);      // 0.05% slippage on entry
    private static final BigDecimal SLIPPAGE_EXIT_BPS = BigDecimal.valueOf(8);       // 0.08% on exit
    private static final long TRADE_MONITOR_INTERVAL_SECONDS = 5;  // Check every 5 seconds
    private static final long T1_TIMEOUT_SECONDS = 900;            // 15 minutes for T1
    private static final long TRADE_MAX_HOLD_SECONDS = 1800;       // 30 minutes max hold

    public PaperTradingExecutor(IndexHuntService indexHuntService) {
        this.indexHuntService = indexHuntService;
    }

    /**
     * Execute a signal (simulate buying options)
     *
     * Paper trading: No real money, just track entry + simulated outcomes
     */
    public PaperTradeResult executeSignal(IndexSignal signal) {
        if (signal == null) {
            return new PaperTradeResult(false, "signal_null");
        }

        try {
            // Step 1: Apply entry slippage (worse fill)
            BigDecimal slippageMultiplier = BigDecimal.ONE.add(
                    SLIPPAGE_ENTRY_BPS.divide(BigDecimal.valueOf(10000), 6, RoundingMode.HALF_UP)
            );
            BigDecimal actualEntryPremium = signal.getOptionEntryPremium()
                    .multiply(slippageMultiplier)
                    .setScale(2, RoundingMode.HALF_UP);

            // Step 2: Mark as filled in database
            indexHuntService.markSignalFilled(signal.getSignalId(), actualEntryPremium);

            // Step 3: Create paper trade tracker
            PaperTrade trade = new PaperTrade();
            trade.signalId = signal.getSignalId();
            trade.indexName = signal.getIndexName();
            trade.direction = signal.getDirection();
            trade.entryTime = Instant.now();
            trade.entryPremium = actualEntryPremium;
            trade.slTarget = signal.getOptionSL().multiply(slippageMultiplier); // Apply slippage to SL too
            trade.t1Target = signal.getOptionT1().multiply(slippageMultiplier); // Apply slippage to T1
            trade.t2Target = signal.getOptionT2();
            trade.qualityScore = signal.getQualityScore();
            trade.lotSize = "NIFTY".equalsIgnoreCase(signal.getIndexName()) ? 25 : 15;

            activeTrades.put(signal.getSignalId(), trade);

            log.info("PAPER_TRADE.executed signal_id={} index={} direction={} entry_premium={} " +
                    "sl={} t1={} quality={}",
                    signal.getSignalId(), signal.getIndexName(), signal.getDirection(),
                    actualEntryPremium, trade.slTarget, trade.t1Target, signal.getQualityScore());

            return new PaperTradeResult(true, "execution_successful");

        } catch (Exception e) {
            log.error("PAPER_TRADE.execution_error signal_id={} error={}", signal.getSignalId(), e.getMessage());
            return new PaperTradeResult(false, "execution_error: " + e.getMessage());
        }
    }

    /**
     * Monitor active trades and check for T1/SL hits
     * Called periodically (every 5-30 seconds)
     */
    public void monitorActiveTrades(Map<String, BigDecimal> currentPrices) {
        List<Long> toClose = new ArrayList<>();

        for (Long signalId : activeTrades.keySet()) {
            PaperTrade trade = activeTrades.get(signalId);
            BigDecimal currentPrice = currentPrices.getOrDefault(trade.indexName, BigDecimal.ZERO);

            if (currentPrice.signum() == 0) {
                continue; // No price data
            }

            // Check timeout (T1 should hit within 15 minutes)
            long holdSeconds = ChronoUnit.SECONDS.between(trade.entryTime, Instant.now());
            if (holdSeconds > TRADE_MAX_HOLD_SECONDS) {
                // Trade expired
                closeTrade(trade, "EXPIRED", trade.entryPremium, "Trade exceeded 30 min hold time");
                toClose.add(signalId);
                continue;
            }

            // Simulate option premium movement based on underlying move
            // Simplified: option premium moves at 2x the underlying move
            BigDecimal underlyingMovePercent = currentPrice.subtract(trade.lastKnownPrice)
                    .divide(trade.lastKnownPrice, 6, RoundingMode.HALF_UP);
            BigDecimal simulatedPremiumMove = underlyingMovePercent.multiply(BigDecimal.valueOf(2));
            BigDecimal currentOptionPrice = trade.entryPremium
                    .multiply(BigDecimal.ONE.add(simulatedPremiumMove))
                    .setScale(2, RoundingMode.HALF_UP);

            // Check for T1 hit (profit target)
            if ("CE".equals(trade.direction) && currentOptionPrice.compareTo(trade.t1Target) >= 0) {
                BigDecimal exitPrice = trade.t1Target.multiply(
                        BigDecimal.ONE.add(SLIPPAGE_EXIT_BPS.divide(BigDecimal.valueOf(10000), 6, RoundingMode.HALF_UP))
                );
                closeTrade(trade, "WIN", exitPrice, "T1 hit at " + trade.t1Target);
                toClose.add(signalId);
                continue;
            }

            // Check for SL hit (loss limit)
            if (currentOptionPrice.compareTo(trade.slTarget) <= 0) {
                BigDecimal exitPrice = trade.slTarget.multiply(
                        BigDecimal.ONE.subtract(SLIPPAGE_EXIT_BPS.divide(BigDecimal.valueOf(10000), 6, RoundingMode.HALF_UP))
                );
                closeTrade(trade, "LOSS", exitPrice, "SL hit at " + trade.slTarget);
                toClose.add(signalId);
                continue;
            }

            // Update last known price for next iteration
            trade.lastKnownPrice = currentPrice;
        }

        // Remove closed trades
        for (Long signalId : toClose) {
            activeTrades.remove(signalId);
        }
    }

    /**
     * Close a trade and record outcome
     */
    private void closeTrade(PaperTrade trade, String outcome, BigDecimal exitPrice, String notes) {
        try {
            BigDecimal pnlPerContract = exitPrice.subtract(trade.entryPremium);
            BigDecimal totalPnL = pnlPerContract.multiply(BigDecimal.valueOf(trade.lotSize));

            String outcomeType = outcome.equals("WIN") ? "WIN" : outcome.equals("LOSS") ? "LOSS" : "EXPIRED";

            indexHuntService.markSignalClosed(
                    trade.signalId,
                    exitPrice,
                    outcomeType,
                    totalPnL
            );

            log.info("PAPER_TRADE.closed signal_id={} index={} outcome={} entry={} exit={} " +
                    "pnl_per_contract={} total_pnl={} quality={} notes={}",
                    trade.signalId, trade.indexName, outcome, trade.entryPremium, exitPrice,
                    pnlPerContract, totalPnL, trade.qualityScore, notes);

        } catch (Exception e) {
            log.error("PAPER_TRADE.close_error signal_id={} error={}", trade.signalId, e.getMessage());
        }
    }

    /**
     * Get active paper trades
     */
    public List<PaperTrade> getActiveTrades() {
        return new ArrayList<>(activeTrades.values());
    }

    /**
     * Get paper trading statistics
     */
    public PaperTradingStats getStats() {
        IndexHuntService.IndexHuntDailyStats dailyStats = indexHuntService.getDailyStats();

        PaperTradingStats stats = new PaperTradingStats();
        stats.totalTrades = dailyStats.totalTrades;
        stats.wins = dailyStats.wins;
        stats.losses = dailyStats.losses;
        stats.winRate = dailyStats.winRate;
        stats.totalPnL = dailyStats.totalPnL;
        stats.activeTrades = activeTrades.size();
        stats.timestamp = Instant.now();

        return stats;
    }

    /**
     * Reset paper trading (clear all trades - for testing only)
     */
    public void resetPaperTrading() {
        activeTrades.clear();
        log.warn("PAPER_TRADE.reset_all_trades");
    }

    // ===== DTOs =====

    public static class PaperTrade {
        public Long signalId;
        public String indexName;
        public String direction;          // CE or PE
        public Instant entryTime;
        public BigDecimal entryPremium;
        public BigDecimal slTarget;
        public BigDecimal t1Target;
        public BigDecimal t2Target;
        public BigDecimal qualityScore;
        public int lotSize;
        public BigDecimal lastKnownPrice = BigDecimal.ZERO; // For monitoring

        public long getHoldSeconds() {
            return ChronoUnit.SECONDS.between(entryTime, Instant.now());
        }
    }

    public static class PaperTradeResult {
        public boolean success;
        public String message;

        public PaperTradeResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public static class PaperTradingStats {
        public int totalTrades;
        public long wins;
        public long losses;
        public double winRate;
        public BigDecimal totalPnL;
        public int activeTrades;
        public Instant timestamp;

        @Override
        public String toString() {
            return String.format(
                    "PAPER_TRADING: Trades=%d | Wins=%d | Losses=%d | WR=%.1f%% | PnL=%s | Active=%d",
                    totalTrades, wins, losses, winRate, totalPnL, activeTrades
            );
        }
    }
}
