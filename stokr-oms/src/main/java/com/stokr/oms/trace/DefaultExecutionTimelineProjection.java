package com.stokr.oms.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.oms.domain.OmsExecutionEvent;
import com.stokr.oms.repository.OmsExecutionEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultExecutionTimelineProjection implements ExecutionTimelineProjection {

    private final OmsExecutionEventRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ExecutionTraceEvent> timelineForOrder(UUID orderId) {
        List<OmsExecutionEvent> rows = repository.findByOrder_IdAndDeletedFalseOrderByStreamSequenceAsc(orderId);
        List<ExecutionTraceEvent> out = new ArrayList<>(rows.size());
        for (OmsExecutionEvent e : rows) {
            Map<String, Object> payload = parsePayload(e.getEventPayloadJson());
            out.add(new ExecutionTraceEvent(
                    e.getEventType() != null ? e.getEventType().name() : "",
                    e.getStreamSequence(),
                    e.getCreatedAt() != null ? e.getCreatedAt().toString() : "",
                    payload
            ));
        }
        return out;
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return new LinkedHashMap<>(Map.of("parseError", ex.getClass().getSimpleName(), "raw", json));
        }
    }
}
