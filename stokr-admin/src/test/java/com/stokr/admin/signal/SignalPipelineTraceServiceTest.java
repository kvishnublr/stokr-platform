package com.stokr.admin.signal;

import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.pipeline.SignalPipelineAuditRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.oms.repository.OmsExecutionEventRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignalPipelineTraceServiceTest {

    @Mock
    private StrategySignalRepository signalRepo;
    @Mock
    private SignalPipelineAuditRepository auditRepo;
    @Mock
    private OmsOrderRepository orderRepo;
    @Mock
    private OmsExecutionEventRepository executionEventRepo;
    @Mock
    private AuthUserRepository authUserRepo;

    @InjectMocks
    private SignalPipelineTraceService service;

    @Test
    void buildTrace_handlesNullOptionalFieldsWithoutNpe() {
        UUID signalId = UUID.randomUUID();
        StrategySignalEntity signal = new StrategySignalEntity();
        signal.setId(signalId);
        signal.setCreatedAt(Instant.now());
        signal.setConfidenceScore(null);
        signal.setSignalType(null);
        signal.setSignalSource(null);

        when(signalRepo.findById(signalId)).thenReturn(Optional.of(signal));
        when(auditRepo.findBySignalIdOrderByCreatedAtAsc(signalId)).thenReturn(List.of());
        when(orderRepo.findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(signalId)).thenReturn(List.of());

        SignalPipelineTraceDto trace = service.buildTrace(signalId);

        assertNotNull(trace);
        assertNotNull(trace.applicationPipeline());
    }
}
