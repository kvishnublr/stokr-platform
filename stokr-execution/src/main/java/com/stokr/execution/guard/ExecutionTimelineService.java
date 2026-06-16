package com.stokr.execution.guard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExecutionTimelineService {

    private final ExecutionTimelineEventRepository timelineEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void append(
            UUID timelineId,
            String correlationId,
            UUID userId,
            UUID signalId,
            String strategyKey,
            String symbol,
            String eventType,
            Instant eventTime,
            Map<String, Object> payload,
            Long latencyMs,
            Map<String, Object> marketSnapshot,
            String guardOutcome,
            String severity
    ) {
        ExecutionTimelineEvent row = new ExecutionTimelineEvent();
        row.setTimelineId(timelineId);
        row.setCorrelationId(correlationId);
        row.setUserId(userId);
        row.setSignalId(signalId);
        row.setStrategyKey(strategyKey);
        row.setSymbol(symbol);
        row.setEventType(eventType);
        row.setEventTime(eventTime == null ? Instant.now() : eventTime);
        row.setPayload(toJson(payload));
        row.setLatencyMs(latencyMs);
        row.setMarketSnapshot(toJson(marketSnapshot));
        row.setGuardOutcome(guardOutcome);
        row.setSeverity(severity);
        timelineEventRepository.save(row);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentForUser(UUID userId, int limit) {
        return timelineEventRepository.findByUserIdAndDeletedFalseOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.max(1, Math.min(500, limit))))
                .stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId().toString());
                    m.put("timelineId", e.getTimelineId() != null ? e.getTimelineId().toString() : null);
                    m.put("eventType", e.getEventType());
                    m.put("eventTime", e.getEventTime() != null ? e.getEventTime().toString() : null);
                    m.put("symbol", e.getSymbol());
                    m.put("strategyKey", e.getStrategyKey());
                    m.put("guardOutcome", e.getGuardOutcome());
                    m.put("severity", e.getSeverity());
                    m.put("latencyMs", e.getLatencyMs());
                    m.put("payload", e.getPayload());
                    m.put("marketSnapshot", e.getMarketSnapshot());
                    return m;
                }).toList();
    }

    private String toJson(Object payload) {
        if (payload == null) return null;
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }
}

