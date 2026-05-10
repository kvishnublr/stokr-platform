package com.stokr.backtest.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class TradeSimulator {

    public BigDecimal simulateFill(BigDecimal price, BigDecimal qty, BigDecimal feesBps) {
        BigDecimal gross = price.multiply(qty);
        BigDecimal fees = gross.multiply(feesBps).divide(BigDecimal.valueOf(10_000));
        return gross.subtract(fees);
    }
}
