package com.stokr.execution.service;

import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Calculates real-time P&L for open positions based on current market prices
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PositionPnLCalculatorService {

    private final OmsExecutionRepository executionRepository;
    private final PortfolioPositionRepository portfolioPositionRepository;

    /**
     * Calculate unrealized P&L for a position
     * P&L = (Current Price - Avg Entry Price) * Quantity
     */
    public BigDecimal calculateUnrealizedPnL(UUID positionId, BigDecimal currentPrice) {
        return portfolioPositionRepository.findById(positionId)
                .map(pos -> {
                    if (pos.getAvgPrice() == null || currentPrice == null) {
                        return BigDecimal.ZERO;
                    }
                    BigDecimal priceChange = currentPrice.subtract(pos.getAvgPrice());
                    return priceChange.multiply(pos.getQuantity());
                })
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Calculate P&L percentage for position
     * P&L% = (Current Price - Avg Entry Price) / Avg Entry Price * 100
     */
    public BigDecimal calculatePnLPercentage(UUID positionId, BigDecimal currentPrice) {
        return portfolioPositionRepository.findById(positionId)
                .map(pos -> {
                    if (pos.getAvgPrice() == null || pos.getAvgPrice().compareTo(BigDecimal.ZERO) == 0) {
                        return BigDecimal.ZERO;
                    }
                    BigDecimal current = currentPrice == null ? BigDecimal.ZERO : currentPrice;
                    BigDecimal direction = pos.getQuantity() != null && pos.getQuantity().signum() < 0
                            ? BigDecimal.ONE.negate()
                            : BigDecimal.ONE;
                    BigDecimal priceChange = current.subtract(pos.getAvgPrice()).multiply(direction);
                    return priceChange.divide(pos.getAvgPrice(), 6, java.math.RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                })
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Check if position should exit based on profit target
     * Exit if: unrealizedPnL% >= targetProfit%
     */
    public boolean shouldExitForProfit(UUID positionId, BigDecimal currentPrice, BigDecimal targetProfitPercent) {
        BigDecimal pnlPercent = calculatePnLPercentage(positionId, currentPrice);
        boolean shouldExit = pnlPercent.compareTo(targetProfitPercent) >= 0;

        if (shouldExit) {
            log.warn("position.profit_target_hit positionId={} pnlPercent={} targetPercent={}",
                    positionId, pnlPercent, targetProfitPercent);
        }

        return shouldExit;
    }

    /**
     * Check if position should exit based on stop loss
     * Exit if: unrealizedPnL% <= -stopLossPercent%
     */
    public boolean shouldExitForStopLoss(UUID positionId, BigDecimal currentPrice, BigDecimal stopLossPercent) {
        BigDecimal pnlPercent = calculatePnLPercentage(positionId, currentPrice);
        BigDecimal negativeStopLoss = stopLossPercent.negate();
        boolean shouldExit = pnlPercent.compareTo(negativeStopLoss) <= 0;

        if (shouldExit) {
            log.warn("position.stop_loss_hit positionId={} pnlPercent={} stoplossPercent={}",
                    positionId, pnlPercent, stopLossPercent);
        }

        return shouldExit;
    }
}
