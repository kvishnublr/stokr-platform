package com.stokr.admin.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.common.simulation.SimulationScenarioContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OperationalEventStore {

    private final OperationalAuditEventRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void append(String topic, Map<String, Object> payload, String actor, UUID targetUserId) {
        OperationalAuditEvent row = new OperationalAuditEvent();
        row.setTopic(topic);
        row.setActor(actor);
        row.setTargetUserId(targetUserId);
        if (SimulationScenarioContext.active()) {
            row.setSimulation(true);
            row.setSimulationRunId(SimulationScenarioContext.runId());
            if (SimulationScenarioContext.scenario() != null) {
                row.setSimulationScenario(SimulationScenarioContext.scenario().name());
            }
        }
        try {
            row.setPayloadJson(objectMapper.writeValueAsString(payload != null ? payload : Map.of()));
        } catch (JsonProcessingException e) {
            row.setPayloadJson("{}");
        }
        repository.save(row);
    }

    @Transactional(readOnly = true)
    public java.util.List<Map<String, Object>> recentRows() {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (OperationalAuditEvent e : repository.findTop50ByDeletedFalseOrderByCreatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId().toString());
            m.put("topic", e.getTopic());
            m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
            m.put("actor", e.getActor());
            m.put("targetUserId", e.getTargetUserId() != null ? e.getTargetUserId().toString() : null);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = objectMapper.readValue(e.getPayloadJson(), Map.class);
                m.put("payload", body);
            } catch (Exception ex) {
                m.put("payload", Map.of("raw", e.getPayloadJson()));
            }
            out.add(m);
        }
        return out;
    }
}
