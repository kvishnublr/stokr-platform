package com.stokr.strategy.pipeline;

import com.stokr.common.pipeline.OmsIntentDispatcher;
import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import com.stokr.common.simulation.SimulationModeService;
import com.stokr.common.runtime.ExecutionPipelineRuntimeReadinessService;
import com.stokr.common.telemetry.SignalDistributionTelemetryService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.service.SignalEmissionGuardService;
import com.stokr.strategy.service.SignalPriceEnrichmentService;
import com.stokr.strategy.service.SignalProvenanceResolver;
import com.stokr.strategy.service.SignalQualityGateService;
import com.stokr.strategy.service.SignalSymbolPriceGateService;
import com.stokr.strategy.service.StrategyDailySignalCapService;
import com.stokr.strategy.signals.SignalProvenance;
import com.stokr.strategy.signals.SignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategySignalPipelineServiceReplayTest {

    @Mock
    private StrategySignalRepository signalRepository;
    @Mock
    private StrategyInstanceRepository strategyInstanceRepository;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private OmsIntentDispatcher omsIntentDispatcher;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private SignalDistributionTelemetryService signalDistributionTelemetryService;
    @Mock
    private ExecutionPipelineRuntimeReadinessService executionPipelineRuntimeReadinessService;
    @Mock
    private SignalPriceEnrichmentService signalPriceEnrichmentService;
    @Mock
    private SignalEmissionGuardService signalEmissionGuardService;
    @Mock
    private SignalProvenanceResolver signalProvenanceResolver;
    @Mock
    private SignalSymbolPriceGateService signalSymbolPriceGateService;
    @Mock
    private SignalQualityGateService signalQualityGateService;
    @Mock
    private StrategyDailySignalCapService dailySignalCapService;
    @Mock
    private SimulationModeService simulationModeService;

    @InjectMocks
    private StrategySignalPipelineService service;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "signalSessionGuardEnabled", false);
        ReflectionTestUtils.setField(service, "replayDispatchToOms", false);
        ReflectionTestUtils.setField(service, "systemUserId", UUID.fromString("33333333-3333-3333-3333-333333333333"));
        when(executionPipelineRuntimeReadinessService.canRouteExecutionMode(any())).thenReturn(true);
        when(simulationModeService.bypassSessionGuard()).thenReturn(false);
        when(strategyInstanceRepository.findAllRunningByStrategyKey(any())).thenReturn(List.of());
    }

    @Test
    void replaySignalsPersistWhenRabbitPipelineDisabled() {
        when(executionPipelineRuntimeReadinessService.canRouteExecutionMode(any())).thenReturn(false);

        StrategySignalEntity signal = new StrategySignalEntity();
        signal.setId(UUID.randomUUID());
        signal.setUserId(UUID.randomUUID());
        signal.setSymbol("RELIANCE");
        signal.setStrategyName("NSE_SPIKE_DETECTION");
        signal.setSignalType(SignalType.BUY);
        signal.setSignalSource(SignalProvenance.REPLAY);

        when(signalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        try {
            StrategySignalEntity saved = service.persistAndDispatch(signal, "cid", "PAPER", SignalProvenance.REPLAY);
            assertThat(saved).isNotNull();
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(signalRepository).save(any());
        verify(rabbitTemplate, never()).convertAndSend(eq(PipelineQueues.STRATEGY_SIGNAL), any(SignalPersistedMessage.class));
        verify(omsIntentDispatcher, never()).dispatch(any(), eq(true));
    }

    @Test
    void replaySignalsDoNotFanOutToOmsByDefault() {
        StrategySignalEntity signal = new StrategySignalEntity();
        signal.setId(UUID.randomUUID());
        signal.setUserId(UUID.randomUUID());
        signal.setSymbol("NSE:INFY");
        signal.setStrategyName("EARLY_BREAKOUT");
        signal.setSignalType(SignalType.BUY);
        signal.setSignalSource(SignalProvenance.REPLAY);

        when(signalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.persistAndDispatch(signal, "cid", "LIVE", SignalProvenance.REPLAY);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(rabbitTemplate, never()).convertAndSend(eq(PipelineQueues.STRATEGY_SIGNAL), any(SignalPersistedMessage.class));
        verify(omsIntentDispatcher, never()).dispatch(any(), eq(true));
    }

    @Test
    void replaySignalsUseSimulatedModeWhenOmsDispatchEnabled() {
        ReflectionTestUtils.setField(service, "replayDispatchToOms", true);

        StrategySignalEntity signal = new StrategySignalEntity();
        signal.setId(UUID.randomUUID());
        signal.setUserId(UUID.randomUUID());
        signal.setSymbol("NSE:INFY");
        signal.setStrategyName("EARLY_BREAKOUT");
        signal.setSignalType(SignalType.BUY);
        signal.setSignalSource(SignalProvenance.REPLAY);

        when(signalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.persistAndDispatch(signal, "cid", "LIVE", SignalProvenance.REPLAY);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        ArgumentCaptor<SignalPersistedMessage> captor = ArgumentCaptor.forClass(SignalPersistedMessage.class);
        verify(omsIntentDispatcher).dispatch(captor.capture(), eq(true));
        assertThat(captor.getValue().executionMode()).isEqualTo("SIMULATED");
    }
}
