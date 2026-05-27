package com.stokr.execution.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OmsExecutionDedupeServiceTest {

    @Mock
    private OmsExecutionDedupeKeyRepository repository;

    private OmsExecutionDedupeService service;

    @BeforeEach
    void setUp() {
        service = new OmsExecutionDedupeService(repository);
        ReflectionTestUtils.setField(service, "zone", ZoneId.of("Asia/Kolkata"));
        ReflectionTestUtils.setField(service, "windowSeconds", 300L);
    }

    @Test
    void rejectsDuplicateWithinWindow() {
        when(repository.findActiveByKey(any(), any())).thenReturn(Optional.of(new OmsExecutionDedupeKey()));

        assertFalse(service.tryAcquire("GAP_FILL", "RELIANCE", "BUY", UUID.randomUUID(), UUID.randomUUID(), Instant.now()));
        verify(repository, never()).save(any());
    }

    @Test
    void acquiresWhenNoDuplicate() {
        when(repository.findActiveByKey(any(), any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(service.tryAcquire("GAP_FILL", "RELIANCE", "BUY", UUID.randomUUID(), UUID.randomUUID(), Instant.now()));
        verify(repository).save(any(OmsExecutionDedupeKey.class));
    }
}
