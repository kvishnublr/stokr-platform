package com.stokr.oms.dto;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioExposureDto(
        List<SymbolExposure> bySymbol,
        List<BrokerExposure> byBrokerNotional
) {
    public record SymbolExposure(
            String symbol,
            BigDecimal quantity,
            BigDecimal exposureNotional,
            BigDecimal omsQuantity,
            String quantitySource,
            String parityState
    ) {
        public SymbolExposure(String symbol, BigDecimal quantity, BigDecimal exposureNotional) {
            this(symbol, quantity, exposureNotional, null, "OMS", null);
        }
    }

    public record BrokerExposure(String brokerVendor, BigDecimal tradedNotionalApprox) {
    }
}
