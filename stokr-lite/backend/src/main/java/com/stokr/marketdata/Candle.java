package com.stokr.marketdata;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Candle(
        String symbol,
        LocalDateTime timestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume
) {}
