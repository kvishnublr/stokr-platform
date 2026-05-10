package com.stokr.strategy.signals;

import java.math.BigDecimal;

public record StrategySignal(
        SignalType type,
        String symbol,
        BigDecimal suggestedQty,
        String reason
) {
}
