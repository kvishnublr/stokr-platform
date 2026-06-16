package com.stokr.execution.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LiveSignalStaleGuardServiceTest {

    private LiveSignalStaleGuardService service;

    @BeforeEach
    void setUp() {
        service = new LiveSignalStaleGuardService();
        ReflectionTestUtils.setField(service, "scalpMaxSeconds", 30L);
        ReflectionTestUtils.setField(service, "momentumMaxSeconds", 120L);
        ReflectionTestUtils.setField(service, "defaultMaxSeconds", 60L);
    }

    @Test
    void scalpStrategyUsesThirtySecondThreshold() {
        assertEquals(30, service.thresholdSeconds("NSE_SPIKE_DETECTION"));
    }

    @Test
    void momentumStrategyUsesTwoMinuteThreshold() {
        assertEquals(120, service.thresholdSeconds("GAP_FILL"));
    }

    @Test
    void rejectsStaleLiveSignal() {
        Instant generated = Instant.now().minusSeconds(45);
        assertTrue(service.check("NSE_SPIKE_DETECTION", generated, Instant.now()).isPresent());
    }

    @Test
    void acceptsFreshSignal() {
        Instant generated = Instant.now().minusSeconds(10);
        assertTrue(service.check("GAP_FILL", generated, Instant.now()).isEmpty());
    }
}
