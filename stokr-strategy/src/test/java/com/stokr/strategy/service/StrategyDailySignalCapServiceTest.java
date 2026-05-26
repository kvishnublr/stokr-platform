package com.stokr.strategy.service;

import com.stokr.strategy.repository.StrategySignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategyDailySignalCapServiceTest {

    @Mock
    private StrategySignalRepository signalRepository;

    private StrategyDailySignalCapService service;

    @BeforeEach
    void setUp() {
        service = new StrategyDailySignalCapService(signalRepository);
        ReflectionTestUtils.setField(service, "zone", ZoneId.of("Asia/Kolkata"));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "defaultCap", 120);
        ReflectionTestUtils.setField(service, "perStrategy", Map.of("NSE_SPIKE_DETECTION", 80));
    }

    @Test
    void overCapWhenCountReachesLimit() {
        when(signalRepository.countProductionSignalsForStrategySince(eq("NSE_SPIKE_DETECTION"), any(Instant.class)))
                .thenReturn(80L);
        assertTrue(service.isOverCap("NSE_SPIKE_DETECTION", Instant.now()));
    }

    @Test
    void underCapWhenBelowLimit() {
        when(signalRepository.countProductionSignalsForStrategySince(eq("NSE_SPIKE_DETECTION"), any(Instant.class)))
                .thenReturn(10L);
        assertFalse(service.isOverCap("NSE_SPIKE_DETECTION", Instant.now()));
    }
}
