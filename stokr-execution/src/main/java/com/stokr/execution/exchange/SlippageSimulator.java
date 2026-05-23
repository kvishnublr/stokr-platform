package com.stokr.execution.exchange;

import com.stokr.common.execution.DeterministicExecutionRng;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Simulates realistic slippage on order execution.
 * Slippage depends on:
 * - Order size (larger orders = more slippage)
 * - Volatility (higher volatility = more slippage)
 * - Side (buy side pays more slippage than sell side typically)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlippageSimulator {

    @Value("${stokr.simulation.base-slippage-bps:1.0}")
    private BigDecimal baseSlippageBps;

    /**
     * Calculate slippage for a limit order fill.
     * Larger orders and worse market conditions = more slippage.
     */
    public BigDecimal calculateSlippage(BigDecimal fillQty, BigDecimal limitPrice,
                                        BigDecimal totalQty) {
        // Base slippage
        BigDecimal slippage = baseSlippageBps;

        // Size-based slippage (larger orders slip more)
        if (totalQty.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal sizeRatio = fillQty.divide(totalQty, 8, RoundingMode.HALF_UP);
            // Orders > 50% of typical order book get additional slippage
            if (sizeRatio.compareTo(new BigDecimal("0.5")) > 0) {
                slippage = slippage.add(new BigDecimal("0.5"));
            }
            // Orders > 100% get even more
            if (sizeRatio.compareTo(BigDecimal.ONE) > 0) {
                slippage = slippage.add(new BigDecimal("1.0"));
            }
        }

        // Add small jitter for realism (0.97x to 1.06x multiplier)
        double jitter = 0.97 + (Math.random() * 0.09);
        slippage = slippage.multiply(BigDecimal.valueOf(jitter));

        return slippage.max(BigDecimal.ZERO);
    }

    /**
     * Calculate slippage for a market order.
     * Market orders always have more slippage than limit orders.
     */
    public BigDecimal calculateMarketSlippage() {
        // Market orders get at least 2x the base slippage
        BigDecimal marketSlippage = baseSlippageBps.multiply(BigDecimal.valueOf(2));

        // Add randomness (0.8x to 1.2x)
        double jitter = 0.8 + (Math.random() * 0.4);
        marketSlippage = marketSlippage.multiply(BigDecimal.valueOf(jitter));

        return marketSlippage;
    }

    /**
     * Calculate slippage with volatility adjustment.
     * During high volatility periods, slippage increases.
     */
    public BigDecimal calculateSlippageWithVolatility(BigDecimal fillQty,
                                                       BigDecimal limitPrice,
                                                       BigDecimal totalQty,
                                                       BigDecimal volatilityLevel) {
        BigDecimal baseSlip = calculateSlippage(fillQty, limitPrice, totalQty);

        // Volatility multiplier (1.0 at normal, up to 1.5x at high volatility)
        BigDecimal volMultiplier = BigDecimal.ONE.add(
                volatilityLevel.multiply(new BigDecimal("0.5"))
        ).min(new BigDecimal("1.5"));

        return baseSlip.multiply(volMultiplier);
    }
}
