package com.stokr.execution.guard;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ExecutionGuardRealtimePublisher {

    private final ApplicationEventPublisher eventPublisher;

    public void publish(
            UUID userId,
            String severity,
            String outcome,
            String symbol,
            String strategyKey,
            String eventType,
            Map<String, Object> payload
    ) {
        eventPublisher.publishEvent(new ExecutionGuardRealtimeEvent(
                eventType,
                Instant.now(),
                userId,
                severity,
                outcome,
                symbol,
                strategyKey,
                payload
        ));
    }
}

