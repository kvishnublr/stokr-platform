package com.stokr.arbitrage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class OpportunityDefaultsTest {
    @Test
    void missingPricesBecomeZeroBeforeSave() {
        OptionArbOpportunity o = OptionArbOpportunity.builder().underlying("NIFTY").build();
        o.defaultMissingPrices();
        assertEquals(BigDecimal.ZERO, o.getSpotPrice());
        assertEquals(BigDecimal.ZERO, o.getFuturesPrice());
        o.setFuturesPrice(BigDecimal.valueOf(23110));
        o.defaultMissingPrices();
        assertEquals(BigDecimal.valueOf(23110), o.getFuturesPrice());
    }
}
