package com.stokr.strategy.pipeline;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.common.events.SignalPublishedEvent;
import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import com.stokr.common.telemetry.SignalDistributionTelemetryService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategySignalPipelineService {

    private final StrategySignalRepository signalRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final SignalDistributionTelemetryService signalDistributionTelemetryService;

    @Transactional
    public StrategySignalEntity persistAndDispatch(StrategySignalEntity signal, String correlationId, String executionMode) {
        StrategySignalEntity saved = signalRepository.save(signal);

        String cid = (correlationId == null || correlationId.isBlank()) ? java.util.UUID.randomUUID().toString() : correlationId;

        SignalPersistedMessage msg = new SignalPersistedMessage(
                saved.getId(),
                saved.getUserId(),
                cid,
                saved.getBacktestRunId(),
                executionMode
        );

        long dispatchLatencyStartNanos = System.nanoTime();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rabbitTemplate.convertAndSend(PipelineQueues.STRATEGY_SIGNAL, msg);
                rabbitTemplate.convertAndSend(PipelineQueues.OMS_ORDER, msg);
                String sk = saved.getStrategyName() != null ? saved.getStrategyName() : StrategySignalEntity.STRATEGY_KEY;
                eventPublisher.publishEvent(new OperationalRealtimeEvent("signal_routed", java.util.Map.of(
                        "signalId", saved.getId().toString(),
                        "userId", saved.getUserId().toString(),
                        "strategyKey", sk,
                        "executionMode", executionMode != null ? executionMode : ""
                )));
                eventPublisher.publishEvent(new SignalPublishedEvent(saved.getId(), saved.getUserId(), saved.getSymbol(), sk));
                signalDistributionTelemetryService.recordPipelineDispatchNanos(System.nanoTime() - dispatchLatencyStartNanos);
                log.info("signal.dispatched signalId={}", saved.getId());
            }
        });

        return saved;
    }
}
