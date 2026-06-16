package com.stokr.intraday.metrics.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OrderFlowSignalEnhancementTest {

    @Test
    void helperMethodsTreatMissingErrorFlagAsFalse() {
        OrderFlowSignalEnhancement signal = OrderFlowSignalEnhancement.builder()
                .symbol("TATASTEEL")
                .buyerPressureScore(50)
                .sellerPressureScore(50)
                .liquidityScore(60)
                .confidence(50)
                .build();

        assertDoesNotThrow(signal::isNeutral);
        assertFalse(signal.getError());
        assertEquals("WEAK", signal.getSignalStrength());
        assertEquals(1.0, signal.getConfidenceMultiplier(), 0.01);
    }
}
