package com.stokr.strategy.lifecycle;

import com.stokr.strategy.repository.StrategySignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategySessionEntryGuardServiceTest {

    @Mock
    private StrategySignalRepository signalRepository;

    private StrategySessionEntryGuardService guard;

    @BeforeEach
    void setUp() {
        guard = new StrategySessionEntryGuardService(signalRepository);
        ReflectionTestUtils.setField(guard, "zone", ZoneId.of("Asia/Kolkata"));
    }

    @Test
    void blocksGapFillWhenSymbolAlreadyTradedThisSession() {
        when(signalRepository.countProductionSignalsForStrategyAndSymbolSince(
                eq("GAP_FILL"), eq("ONGC"), any(Instant.class)))
                .thenReturn(1L);

        assertFalse(guard.isSessionEntryAllowed("GAP_FILL", "ONGC", Instant.parse("2026-05-27T05:00:00Z")));
    }

    @Test
    void allowsFirstGapFillEntryForSymbol() {
        when(signalRepository.countProductionSignalsForStrategyAndSymbolSince(
                eq("GAP_FILL"), eq("ONGC"), any(Instant.class)))
                .thenReturn(0L);

        assertTrue(guard.isSessionEntryAllowed("GAP_FILL", "ONGC", Instant.parse("2026-05-27T05:00:00Z")));
    }
}
