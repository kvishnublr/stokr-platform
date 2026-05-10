package com.stokr.execution.pipeline;

import com.stokr.common.events.SignalPublishedEvent;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists a signal and runs the same OMS → risk → execution path synchronously (no RabbitMQ).
 * Used for deterministic backtests and replay; live trading continues to use {@code StrategySignalPipelineService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalExecutionBridge {

    private final StrategySignalRepository signalRepository;
    private final OrderIntentProcessor orderIntentProcessor;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public StrategySignalEntity persistAndExecuteSynchronously(StrategySignalEntity signal, String correlationId, String executionMode) {
        StrategySignalEntity saved = signalRepository.save(signal);

        String cid = (correlationId == null || correlationId.isBlank()) ? UUID.randomUUID().toString() : correlationId;

        SignalPersistedMessage msg = new SignalPersistedMessage(
                saved.getId(),
                saved.getUserId(),
                cid,
                saved.getBacktestRunId(),
                executionMode
        );

        orderIntentProcessor.processSignalIntent(msg, true);

        String sk = saved.getStrategyName() != null ? saved.getStrategyName() : StrategySignalEntity.STRATEGY_KEY;
        eventPublisher.publishEvent(new SignalPublishedEvent(saved.getId(), saved.getUserId(), saved.getSymbol(), sk));
        log.info("signal.sync.executed signalId={}", saved.getId());

        return saved;
    }
}
