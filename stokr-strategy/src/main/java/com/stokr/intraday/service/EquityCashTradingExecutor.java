package com.stokr.intraday.service;

import com.stokr.intraday.domain.EquitySignal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Equity Cash Trading Executor (Paper Trading Simulation for ADV_CASH)
 *
 * Executes ADV_CASH signals in simulation mode with realistic slippage:
 * - Entry slippage: 0.05% (5 BPS)
 * - Exit slippage: 0.08% (8 BPS)
 * - Monitors for T1/SL hits every 5 seconds
 * - Tracks actual P&L
 */
@Service
@Slf4j
public class EquityCashTradingExecutor {

    // Paper trade tracker
    private final Map<Long, EquityCashTrade> activeTrades = new ConcurrentHashMap<>();
    private final List<EquityCashTrade> closedTrades = Collections.synchronizedList(new ArrayList<>());

    // Configuration
    private static final BigDecimal SLIPPAGE_ENTRY_BPS = BigDecimal.valueOf(0.0005);   // 0.05%
    private static final BigDecimal SLIPPAGE_EXIT_BPS = BigDecimal.valueOf(0.0008);    // 0.08%
    private static final int MONITOR_INTERVAL_SECONDS = 5;
    private static final int T1_TIMEOUT_MINUTES = 15;
    private static final int MAX_HOLD_MINUTES = 120; // 2 hours for equity

    /**
     * Execute an equity cash signal in paper trading
     */
    public EquityCashTradeResult executeSignal(EquitySignal signal) {
        try {
            // Apply entry slippage
            BigDecimal slippageMultiplier = BigDecimal.ONE.add(SLIPPAGE_ENTRY_BPS);
            BigDecimal entryPriceWithSlippage = signal.getDirection().equals("LONG") ?
                    signal.getEntryLevel().multiply(slippageMultiplier) :
                    signal.getEntryLevel().divide(slippageMultiplier);

            // Determine quantity based on position sizing (₹20k per trade)
            BigDecimal positionSize = BigDecimal.valueOf(20000);
            int quantity = positionSize.divide(entryPriceWithSlippage, 0, java.math.RoundingMode.DOWN).intValue();

            if (quantity < 1) {
                quantity = 1;
            }

            // Create paper trade
            EquityCashTrade trade = new EquityCashTrade();
            trade.signalId = signal.getSignalId();
            trade.symbol = signal.getSymbolName();
            trade.direction = signal.getDirection();
            trade.entryPrice = entryPriceWithSlippage;
            trade.currentPrice = entryPriceWithSlippage;
            trade.stopLoss = signal.getStopLossLevel();
            trade.target1 = signal.getTargetLevel1();
            trade.target2 = signal.getTargetLevel2();
            trade.quantity = quantity;
            trade.quality = signal.getQualityScore();
            trade.entryTime = Instant.now();
            trade.isActive = true;

            // Add to active trades
            activeTrades.put(signal.getSignalId(), trade);

            log.info("ADV_CASH_PAPER_TRADE.executed signal_id={} symbol={} direction={} entry={} qty={} sl={} t1={}",
                    signal.getSignalId(), signal.getSymbolName(), signal.getDirection(),
                    entryPriceWithSlippage, quantity, signal.getStopLossLevel(), signal.getTargetLevel1());

            return new EquityCashTradeResult(true, "execution_successful");

        } catch (Exception e) {
            log.error("ADV_CASH_PAPER_TRADE.execution_failed signal_id={} error={}", signal.getSignalId(), e.getMessage());
            return new EquityCashTradeResult(false, "execution_failed: " + e.getMessage());
        }
    }

    /**
     * Monitor active trades for T1/SL hits
     */
    public void monitorActiveTrades(Map<String, BigDecimal> currentPrices) {
        List<Long> closedSignalIds = new ArrayList<>();

        for (Map.Entry<Long, EquityCashTrade> entry : activeTrades.entrySet()) {
            Long signalId = entry.getKey();
            EquityCashTrade trade = entry.getValue();

            if (!trade.isActive) {
                continue;
            }

            BigDecimal currentPrice = currentPrices.get(trade.symbol);
            if (currentPrice == null) {
                continue;
            }

            trade.currentPrice = currentPrice;

            // Check timeout (2 hours for equity cash)
            long elapsedMinutes = (Instant.now().toEpochMilli() - trade.entryTime.toEpochMilli()) / 60000;
            if (elapsedMinutes > MAX_HOLD_MINUTES) {
                closeTrade(trade, currentPrice, "EXPIRED");
                closedSignalIds.add(signalId);
                continue;
            }

            // Check T1 hit (first target)
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
    private void closeTrade(EquityCashTrade trade, BigDecimal exitPrice, String outcome) {
        // Apply exit slippage
        BigDecimal slippageMultiplier = BigDecimal.ONE.add(SLIPPAGE_EXIT_BPS);
        BigDecimal exitPriceWithSlippage = trade.direction.equals("LONG") ?
                exitPrice.divide(slippageMultiplier) :
                exitPrice.multiply(slippageMultiplier);

        // Calculate P&L per share
        BigDecimal pnl;
        if (trade.direction.equals("LONG")) {
            pnl = exitPriceWithSlippage.subtract(trade.entryPrice);
        } else {
            pnl = trade.entryPrice.subtract(exitPriceWithSlippage);
        }

        // Total P&L = per-share PnL × quantity
        BigDecimal totalPnL = pnl.multiply(BigDecimal.valueOf(trade.quantity));

        trade.exitPrice = exitPriceWithSlippage;
        trade.exitTime = Instant.now();
        trade.outcome = outcome;
        trade.pnl = totalPnL;
        trade.isActive = false;

        // Move to closed trades
        closedTrades.add(trade);

        log.info("ADV_CASH_PAPER_TRADE.closed signal_id={} symbol={} outcome={} entry={} exit={} qty={} pnl={}",
                trade.signalId, trade.symbol, outcome, trade.entryPrice, exitPriceWithSlippage, trade.quantity, totalPnL);
    }

    /**
     * Get active trades
     */
    public List<EquityCashTrade> getActiveTrades() {
        return new ArrayList<>(activeTrades.values());
    }

    /**
     * Get trading statistics
     */
    public EquityCashTradingStats getStats() {
        long totalTrades = closedTrades.size();
        long wins = closedTrades.stream().filter(t -> "WIN".equals(t.outcome)).count();
        long losses = closedTrades.stream().filter(t -> "LOSS".equals(t.outcome)).count();
        BigDecimal totalPnL = closedTrades.stream()
                .map(t -> t.pnl != null ? t.pnl : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double winRate = totalTrades > 0 ? (100.0 * wins / totalTrades) : 0.0;

        return new EquityCashTradingStats(totalTrades, wins, losses, winRate, totalPnL, activeTrades.size());
    }

    /**
     * Reset all trades (for testing)
     */
    public void resetTrades() {
        activeTrades.clear();
        closedTrades.clear();
        log.warn("ADV_CASH_PAPER_TRADE.reset_all");
    }

    // ===== DTOs =====

    @Data
    public static class EquityCashTrade {
        public Long signalId;
        public String symbol;
        public String direction;
        public BigDecimal entryPrice;
        public BigDecimal currentPrice;
        public BigDecimal exitPrice;
        public BigDecimal stopLoss;
        public BigDecimal target1;
        public BigDecimal target2;
        public int quantity;
        public BigDecimal quality;
        public Instant entryTime;
        public Instant exitTime;
        public String outcome;
        public BigDecimal pnl;
        public boolean isActive;
    }

    @Data
    @AllArgsConstructor
    public static class EquityCashTradeResult {
        public boolean success;
        public String message;
    }

    @Data
    @AllArgsConstructor
    public static class EquityCashTradingStats {
        public long totalTrades;
        public long wins;
        public long losses;
        public double winRate;
        public BigDecimal totalPnL;
        public int activeTrades;
    }
}
