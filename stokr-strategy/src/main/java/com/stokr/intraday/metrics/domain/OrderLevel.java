package com.stokr.intraday.metrics.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.math.BigDecimal;

/**
 * Single level in an order book (bid or ask).
 * Contains price and volume at that level.
 */
@Data
@AllArgsConstructor
public class OrderLevel {
    private BigDecimal price;
    private long volume;
}
