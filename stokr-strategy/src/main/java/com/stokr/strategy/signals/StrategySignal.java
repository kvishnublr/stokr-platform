package com.stokr.strategy.signals;

import java.math.BigDecimal;

public record StrategySignal(
        SignalType type,
        String symbol,
        BigDecimal suggestedQty,
        String reason,
        BigDecimal entryPrice,
        BigDecimal stopPrice,
        BigDecimal targetPrice
) {
    public StrategySignal(SignalType type, String symbol, BigDecimal suggestedQty, String reason) {
        this(type, symbol, suggestedQty, reason, null, null, null);
    }
}
