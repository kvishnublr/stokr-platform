package com.stokr.oms.dto;

import java.time.Instant;
import java.util.UUID;

public record ExecutionEventRowDto(
        UUID id,
        String eventType,
        String eventPayloadJson,
        Long streamSequence,
        String correlationId,
        Instant createdAt
) {
}
