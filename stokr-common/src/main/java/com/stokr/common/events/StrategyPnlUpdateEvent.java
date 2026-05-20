package com.stokr.common.events;

import java.math.BigDecimal;

public record StrategyPnlUpdateEvent(String strategyKey, BigDecimal realizedPnlDelta) {
}
