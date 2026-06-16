package com.stokr.oms.dto;

import java.math.BigDecimal;

public record OmsSummaryMetricsDto(
        long totalOrders,
        long rejectedOrders,
        long cancelledOrders,
        long fillLegs,
        Double averageLatencyMs,
        BigDecimal averageSlippageBps
) {
}
