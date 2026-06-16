package com.stokr.oms.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.oms.domain.ExecutionEventType;
import com.stokr.oms.domain.OmsExecutionEvent;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.repository.OmsExecutionEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only execution event log for audit, replay reconstruction, and diagnostics (PR-4).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionEventAppendService {

    private final OmsExecutionEventRepository repository;
    private final ObjectMapper deterministicMapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Transactional
    public OmsExecutionEvent append(OmsOrder order, ExecutionEventType type, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (payload != null) {
            body.putAll(payload);
        }
        body.putIfAbsent("orderId", order.getId().toString());
        if (order.getSignalId() != null) {
            body.putIfAbsent("signalId", order.getSignalId().toString());
        }
        if (order.getBacktestRunId() != null) {
            body.putIfAbsent("backtestRunId", order.getBacktestRunId().toString());
        }
        String json;
        try {
            json = deterministicMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("execution event serialization failed", e);
        }
        long next = repository.maxStreamSequence(order.getId()) + 1L;
        OmsExecutionEvent row = new OmsExecutionEvent();
        row.setUserId(order.getUserId());
        row.setOrder(order);
        row.setSignalId(order.getSignalId());
        row.setBacktestRunId(order.getBacktestRunId());
        String cid = CorrelationIdHolder.get();
        if (cid == null || cid.isBlank()) {
            cid = order.getCorrelationId();
        }
        row.setCorrelationId(cid);
        row.setEventType(type);
        row.setEventPayloadJson(json);
        row.setStreamSequence(next);
        OmsExecutionEvent saved = repository.save(row);
        log.debug("execution.event.append orderId={} type={} seq={}", order.getId(), type, next);
        return saved;
    }

    @Transactional(readOnly = true)
    public long countByRunAndType(UUID runId, ExecutionEventType type) {
        return repository.countByBacktestRunIdAndEventTypeAndDeletedFalse(runId, type);
    }
}
