package com.stokr.oms.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PortfolioEquityPointDto(
        Instant asOf,
        BigDecimal cumulativePnl,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl
) {
}
