package com.stokr.strategy.signals;

import java.math.BigDecimal;

public record StrategySignal(
        SignalType type,
        String symbol,
        BigDecimal suggestedQty,
        String reason,
        BigDecimal entryPrice,
        BigDecimal stopPrice,
        BigDecimal targetPrice,
        BigDecimal confidenceScore,
        BigDecimal probability,
        String tradeQuality,
        String confidenceVersion,
        String confidenceBreakdownJson
) {
    public StrategySignal(SignalType type, String symbol, BigDecimal suggestedQty, String reason) {
        this(type, symbol, suggestedQty, reason, null, null, null, null, null, null, null, null);
    }

    public StrategySignal(
            SignalType type,
            String symbol,
            BigDecimal suggestedQty,
            String reason,
            BigDecimal entryPrice,
            BigDecimal stopPrice,
            BigDecimal targetPrice
    ) {
        this(type, symbol, suggestedQty, reason, entryPrice, stopPrice, targetPrice, null, null, null, null, null);
    }

    public StrategySignal withConfidence(
            BigDecimal score,
            BigDecimal probabilityValue,
            String quality,
            String version,
            String breakdownJson
    ) {
        return new StrategySignal(
                type,
                symbol,
                suggestedQty,
                reason,
                entryPrice,
                stopPrice,
                targetPrice,
                score,
                probabilityValue,
                quality,
                version,
                breakdownJson
        );
    }
}
