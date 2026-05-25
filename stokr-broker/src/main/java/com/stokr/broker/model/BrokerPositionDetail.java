package com.stokr.broker.model;

import java.math.BigDecimal;

/**
 * Rich broker position row from Kite portfolio API (net/day).
 */
public record BrokerPositionDetail(
        String exchange,
        String tradingsymbol,
        String symbolKey,
        BigDecimal quantity,
        BigDecimal averagePrice,
        BigDecimal realisedPnl,
        BigDecimal unrealisedPnl,
        String product
) {
}
