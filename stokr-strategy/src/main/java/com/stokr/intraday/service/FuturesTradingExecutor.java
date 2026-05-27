package com.stokr.intraday.service;

import com.stokr.intraday.domain.FuturesSignal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Futures Trading Executor (Paper Trading Simulation)
 *
 * Executes S3 and S7 signals in simulation mode with realistic slippage:
 * - Entry slippage: 0.05% (5 BPS)
 * - Exit slippage: 0.08% (8 BPS)
 * - Monitors for T1/SL hits every 5 seconds
 * - Tracks actual P&L
 */
@Service
@Slf4j
public class FuturesTradingExecutor {

    // Paper trade tracker
    private final Map<Long, FuturesTrade> activeTrades = new ConcurrentHashMap<>();
    private final List<FuturesTrade> closedTrades = Collections.synchronizedList(new ArrayList<>());

    // Configuration
    private static final BigDecimal SLIPPAGE_ENTRY_BPS = BigDecimal.valueOf(0.0005);   // 0.05%
    private static final BigDecimal SLIPPAGE_EXIT_BPS = BigDecimal.valueOf(0.0008);    // 0.08%
    private static final int LOT_SIZE_NIFTY = 25;
    private static final int LOT_SIZE_BANKNIFTY = 15;
    private static final int MONITOR_INTERVAL_SECONDS = 5;
    private static final int T1_TIMEOUT_MINUTES = 15;
    private static final int MAX_HOLD_MINUTES = 30;

    /**
     * Execute a futures signal in paper trading
     */
    public FutureTradeResult executeSignal(FuturesSignal signal) {
        try {
            // Apply entry slippage
            BigDecimal slippageMultiplier = BigDecimal.ONE.add(SLIPPAGE_ENTRY_BPS);
            BigDecimal entryPriceWithSlippage = signal.getDirection().equals("LONG") ?
                    signal.getEntryLevel().multiply(slippageMultiplier) :
                    signal.getEntryLevel().divide(slippageMultiplier);

            // Get lot size
            int lotSize = signal.getSymbolName().equals("NIFTY") ? LOT_SIZE_NIFTY : LOT_SIZE_BANKNIFTY;

            // Create paper trade
            FuturesTrade trade = new FuturesTrade();
            trade.signalId = signal.getSignalId();
            trade.strategy = signal.getStrategyName();
            trade.symbol = signal.getSymbolName();
            trade.direction = signal.getDirection();
            trade.entryPrice = entryPriceWithSlippage;
            trade.currentPrice = entryPriceWithSlippage;
            trade.stopLoss = signal.getStopLossLevel();
            trade.target1 = signal.getTargetLevel1();
            trade.target2 = signal.getTargetLevel2();
            trade.lotSize = lotSize;
            trade.quality = signal.getQualityScore();
            trade.entryTime = Instant.now();
            trade.isActive = true;

            // Add to active trades
            activeTrades.put(signal.getSignalId(), trade);

            log.info("FUTURES_PAPER_TRADE.executed signal_id={} strategy={} symbol={} direction={} entry={} sl={} t1={}",
                    signal.getSignalId(), signal.getStrategyName(), signal.getSymbolName(),
                    signal.getDirection(), entryPriceWithSlippage, signal.getStopLossLevel(),
                    signal.getTargetLevel1());

            return new FutureTradeResult(true, "execution_successful");

        } catch (Exception e) {
            log.error("FUTURES_PAPER_TRADE.execution_failed signal_id={} error={}", signal.getSignalId(), e.getMessage());
            return new FutureTradeResult(false, "execution_failed: " + e.getMessage());
        }
    }

    /**
     * Monitor active trades for T1/SL hits
     */
    public void monitorActiveTrades(Map<String, BigDecimal> currentPrices) {
        List<Long> closedSignalIds = new ArrayList<>();

        for (Map.Entry<Long, FuturesTrade> entry : activeTrades.entrySet()) {
            Long signalId = entry.getKey();
            FuturesTrade trade = entry.getValue();

            if (!trade.isActive) {
                continue;
            }

            BigDecimal currentPrice = currentPrices.get(trade.symbol);
            if (currentPrice == null) {
                continue;
            }

            trade.currentPrice = currentPrice;

            // Check timeout (30 minutes)
            long elapsedMinutes = (Instant.now().toEpochMilli() - trade.entryTime.toEpochMilli()) / 60000;
            if (elapsedMinutes > MAX_HOLD_MINUTES) {
                closeTrade(trade, currentPrice, "EXPIRED");
                closedSignalIds.add(signalId);
                continue;
            }

            // Check T1 hit
            boolean t1Hit = false;
            if (trade.direction.equals("LONG")) {
                t1Hit = currentPrice.compareTo(trade.target1) >= 0;
            } else {
                t1Hit = currentPrice.compareTo(trade.target1) <= 0;
            }

            if (t1Hit) {
                closeTrade(trade, trade.target1, "WIN");
                closedSignalIds.add(signalId);
                continue;
            }

            // Check SL hit
            boolean slHit = false;
            if (trade.direction.equals("LONG")) {
                slHit = currentPrice.compareTo(trade.stopLoss) <= 0;
            } else {
                slHit = currentPrice.compareTo(trade.stopLoss) >= 0;
            }

            if (slHit) {
                closeTrade(trade, trade.stopLoss, "LOSS");
                closedSignalIds.add(signalId);
            }
        }

        // Remove closed trades from active
        for (Long signalId : closedSignalIds) {
            activeTrades.remove(signalId);
        }
    }

    /**
     * Close a trade
     */
    private void closeTrade(FuturesTrade trade, BigDecimal exitPrice, String outcome) {
        // Apply exit slippage
        BigDecimal slippageMultiplier = BigDecimal.ONE.add(SLIPPAGE_EXIT_BPS);
        BigDecimal exitPriceWithSlippage = trade.direction.equals("LONG") ?
                exitPrice.divide(slippageMultiplier) :
                exitPrice.multiply(slippageMultiplier);

        // Calculate P&L
        BigDecimal pnl;
        if (trade.direction.equals("LONG")) {
            pnl = exitPriceWithSlippage.subtract(trade.entryPrice);
        } else {
            pnl = trade.entryPrice.subtract(exitPriceWithSlippage);
        }

        // Multiply by lot size and tick value (100 for rupee conversion)
        BigDecimal totalPnL = pnl.multiply(BigDecimal.valueOf(trade.lotSize * 100));

        trade.exitPrice = exitPriceWithSlippage;
        trade.exitTime = Instant.now();
        trade.outcome = outcome;
        trade.pnl = totalPnL;
        trade.isActive = false;

        // Move to closed trades
        closedTrades.add(trade);

        log.info("FUTURES_PAPER_TRADE.closed signal_id={} strategy={} outcome={} entry={} exit={} pnl={}",
                trade.signalId, trade.strategy, outcome, trade.entryPrice, exitPriceWithSlippage, totalPnL);
    }

    /**
     * Get active trades
     */
    public List<FuturesTrade> getActiveTrades() {
        return new ArrayList<>(activeTrades.values());
    }

    /**
     * Get trading statistics
     */
    public FuturesTradingStats getStats() {
        long totalTrades = closedTrades.size();
        long wins = closedTrades.stream().filter(t -> "WIN".equals(t.outcome)).count();
        long losses = closedTrades.stream().filter(t -> "LOSS".equals(t.outcome)).count();
        BigDecimal totalPnL = closedTrades.stream()
                .map(t -> t.pnl != null ? t.pnl : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double winRate = totalTrades > 0 ? (100.0 * wins / totalTrades) : 0.0;

        return new FuturesTradingStats(totalTrades, wins, losses, winRate, totalPnL, activeTrades.size());
    }

    /**
     * Reset all trades (for testing)
     */
    public void resetTrades() {
        activeTrades.clear();
        closedTrades.clear();
        log.warn("FUTURES_PAPER_TRADE.reset_all");
    }

    // ===== DTOs =====

    @Data
    public static class FuturesTrade {
        public Long signalId;
        public String strategy;
        public String symbol;
        public String direction;
        public BigDecimal entryPrice;
        public BigDecimal currentPrice;
        public BigDecimal exitPrice;
        public BigDecimal stopLoss;
        public BigDecimal target1;
        public BigDecimal target2;
        public int lotSize;
        public BigDecimal quality;
        public Instant entryTime;
        public Instant exitTime;
        public String outcome;
        public BigDecimal pnl;
        public boolean isActive;
    }

    @Data
    @AllArgsConstructor
    public static class FutureTradeResult {
        public boolean success;
        public String message;
    }

    @Data
    @AllArgsConstructor
    public static class FuturesTradingStats {
        public long totalTrades;
        public long wins;
        public long losses;
        public double winRate;
        public BigDecimal totalPnL;
        public int activeTrades;
    }
}
