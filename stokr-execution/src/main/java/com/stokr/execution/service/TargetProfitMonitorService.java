package com.stokr.execution.service;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Monitors open positions and generates EXIT signals when profit targets are hit.
 * Runs every 15 seconds to check if positions should close.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TargetProfitMonitorService {

    private final PortfolioPositionRepository portfolioPositionRepository;
    private final StrategySignalRepository signalRepository;
    private final PositionPnLCalculatorService pnlCalculator;

    @Value("${stokr.execution.target-profit.enabled:true}")
    private boolean profitTargetMonitoringEnabled;

    @Value("${stokr.execution.target-profit-percent:0.45}")
    private BigDecimal defaultTargetProfitPercent;

    private static final UUID PRIMARY_TRADER_ID = UUID.fromString("6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4");

    /**
     * Monitor all open positions every 15 seconds
     * Generate EXIT signals when profit targets are hit
     */
    @Scheduled(fixedDelay = 15000, initialDelay = 30000)
    @Transactional
    public void monitorAndExitProfitPositions() {
        if (!profitTargetMonitoringEnabled) {
            return;
        }

        try {
            int exitsGenerated = 0;

            // Get all open positions
            var positions = portfolioPositionRepository.findByUserIdAndDeletedFalse(PRIMARY_TRADER_ID);

            for (var position : positions) {
                if (position.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
                    continue; // Skip zero positions
                }

                // Use position average price until live market price wiring is available.
                BigDecimal currentPrice = position.getAvgPrice();

                if (currentPrice == null) {
                    continue;
                }

                // Check if profit target hit
                if (pnlCalculator.shouldExitForProfit(position.getId(), currentPrice, defaultTargetProfitPercent)) {
                    generateExitSignal(position.getId(), position.getSymbol(), position.getQuantity());
                    exitsGenerated++;
                }
            }

            if (exitsGenerated > 0) {
                log.warn("target_profit.exits_generated count={}", exitsGenerated);
            }
        } catch (Exception e) {
            log.error("target_profit.monitor_failed error={}", e.getMessage(), e);
        }
    }

    /**
     * Generate EXIT signal for a position that hit profit target
     */
    private void generateExitSignal(UUID positionId, String symbol, BigDecimal quantity) {
        try {
            StrategySignalEntity exitSignal = new StrategySignalEntity();
            exitSignal.setUserId(PRIMARY_TRADER_ID);
            exitSignal.setSignalType(SignalType.EXIT);
            exitSignal.setSymbol(symbol);
            exitSignal.setSuggestedQty(quantity.abs()); // Close full position
            exitSignal.setReason("Profit target reached (" + defaultTargetProfitPercent + "%)");
            exitSignal.setStrategyName("TARGET_PROFIT_EXIT");
            exitSignal.setStrategyVersion("1.0");
            exitSignal.setPipeline("HYBRID_EXIT");
            exitSignal.setTestTrade(false);
            exitSignal.setSimulation(false);

            signalRepository.save(exitSignal);

            log.warn("target_profit.exit_signal_created positionId={} symbol={} qty={} signalId={}",
                    positionId, symbol, quantity, exitSignal.getId());
        } catch (Exception e) {
            log.error("target_profit.exit_signal_failed positionId={} symbol={} error={}",
                    positionId, symbol, e.getMessage());
        }
    }
}
