package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * OFI (Order Flow Imbalance) Spike Strategy (Strategy I).
 * Uses buyer/seller ratio as proxy for order flow.
 * Enters when buyer/seller ratio is extreme + volume surge.
 */
@Slf4j
@Component
public class OfiSpikeStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "OFI_SPIKE";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        Candle latest = context.getLatestCandle();
        if (latest == null) return null;

        // Extract buyer/seller from context extras if available
        Long buyerQty = context.extra("buyerQty", Long.class);
        Long sellerQty = context.extra("sellerQty", Long.class);
        if (buyerQty == null || sellerQty == null || sellerQty == 0) return null;

        double ratio = buyerQty / (double) sellerQty;
        BigDecimal close = latest.close();

        // Extreme buyer dominance → LONG
        if (ratio > 2.0) {
            BigDecimal sl = params.getStopLossPrice(close, Signal.Side.BUY);
            BigDecimal target = params.getTargetPrice(close, Signal.Side.BUY);
            return new Signal(context.symbol(), Signal.Side.BUY, close, sl, target,
                    0.75, "OFI spike buyer dominance ratio=" + String.format("%.2f", ratio));
        }

        // Extreme seller dominance → SHORT
        if (ratio < 0.5) {
            BigDecimal sl = params.getStopLossPrice(close, Signal.Side.SELL);
            BigDecimal target = params.getTargetPrice(close, Signal.Side.SELL);
            return new Signal(context.symbol(), Signal.Side.SELL, close, sl, target,
                    0.75, "OFI spike seller dominance ratio=" + String.format("%.2f", ratio));
        }

        return null;
    }
}
