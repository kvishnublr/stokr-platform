package com.stokr.oms.dto;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioDashboardDto(
        PortfolioOverviewDto overview,
        List<PortfolioEquityPointDto> equityCurve,
        PortfolioExposureDto exposure,
        BigDecimal maxDrawdownPct,
        SymbolPnLBrief bestSymbol,
        SymbolPnLBrief worstSymbol
) {
    public record SymbolPnLBrief(String symbol, BigDecimal unrealizedPnl) {
    }
}
