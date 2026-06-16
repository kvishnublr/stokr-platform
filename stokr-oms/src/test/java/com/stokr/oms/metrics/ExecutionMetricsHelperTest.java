package com.stokr.oms.metrics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionMetricsHelperTest {

    @Test
    void latencyMs_usesOrderCreatedToFillDuration() {
        Instant created = Instant.parse("2026-05-26T04:00:00Z");
        Instant fill = Instant.parse("2026-05-26T04:00:01.500Z");
        assertThat(ExecutionMetricsHelper.latencyMs(created, fill)).isEqualTo(1500L);
    }

    @Test
    void slippageBps_buyPositiveWhenFillAboveReference() {
        BigDecimal bps = ExecutionMetricsHelper.slippageBps(
                new BigDecimal("101.00"),
                new BigDecimal("100.00"),
                "BUY");
        assertThat(bps).isEqualByComparingTo("100");
    }

    @Test
    void slippageBps_sellPositiveWhenFillBelowReference() {
        BigDecimal bps = ExecutionMetricsHelper.slippageBps(
                new BigDecimal("99.00"),
                new BigDecimal("100.00"),
                "SELL");
        assertThat(bps).isEqualByComparingTo("100");
    }
}
