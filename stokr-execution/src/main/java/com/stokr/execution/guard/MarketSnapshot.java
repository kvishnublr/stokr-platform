package com.stokr.execution.guard;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketSnapshot(
        Instant capturedAt,
        BigDecimal ltp,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal topBidQty,
        BigDecimal topAskQty,
        Instant lastTickAt,
        Instant lastHeartbeatAt,
        String websocketState,
        Integer reconnectCount,
        Integer subscriptionCount,
        BigDecimal volatilityPct,
        BigDecimal estimatedSlippagePct,
        boolean marketOpen
) {
}

