package com.stokr.oms.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PortfolioOverviewDto(
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        BigDecimal mtmPnl,
        BigDecimal cumulativePnl,
        int openPositionCount,
        Instant latestSnapshotAt,
        LocalDate latestBusinessDate,
        BigDecimal todayRealized,
        BigDecimal todayUnrealized,
        BigDecimal todayMtm
) {
}
