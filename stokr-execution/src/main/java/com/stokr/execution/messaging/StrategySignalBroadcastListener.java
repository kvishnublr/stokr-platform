package com.stokr.execution.messaging;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import com.stokr.common.telemetry.SignalDistributionTelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Drains {@link PipelineQueues#STRATEGY_SIGNAL} — published for telemetry/realtime broadcast.
 * Without a consumer this queue grows unbounded while the API is healthy.
 */
@Component
@ConditionalOnProperty(prefix = "stokr.rabbit", name = "listeners-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class StrategySignalBroadcastListener {

    private final SignalDistributionTelemetryService signalDistributionTelemetryService;
    private final ApplicationEventPublisher eventPublisher;

    @RabbitListener(queues = PipelineQueues.STRATEGY_SIGNAL, concurrency = "1-2")
    public void onBroadcast(SignalPersistedMessage message) {
        signalDistributionTelemetryService.recordStrategySignalBroadcast(message);
        eventPublisher.publishEvent(new OperationalRealtimeEvent("signal_broadcast", Map.of(
                "signalId", message.signalId() != null ? message.signalId().toString() : "",
                "userId", message.userId() != null ? message.userId().toString() : "",
                "executionMode", message.executionMode() != null ? message.executionMode() : ""
        )));
        log.debug("signal.broadcast.consumed signalId={}", message.signalId());
    }
}
