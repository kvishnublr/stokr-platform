package com.stokr.strategy.sdk.context;

import java.math.BigDecimal;

public record RiskContext(
        BigDecimal maxOrderQty,
        BigDecimal maxNotional,
        boolean killSwitchEngaged,
        boolean liveOrdersAllowed
) {
}
