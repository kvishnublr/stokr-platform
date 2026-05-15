package com.stokr.oms.trace;

import com.stokr.oms.domain.ExecutionEventType;
import com.stokr.oms.domain.OmsExecutionEvent;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.service.ExecutionEventAppendService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enriched append-only execution tracing (latency hints on every event row).
 */
@Service
@RequiredArgsConstructor
public class ExecutionTraceService {

    private final ExecutionEventAppendService executionEventAppendService;

    public OmsExecutionEvent trace(OmsOrder order, ExecutionEventType type, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (payload != null) {
            body.putAll(payload);
        }
        body.put("traceWallClock", Instant.now().toString());
        if (order.getCreatedAt() != null) {
            body.put("latencySinceOrderCreateMs", Duration.between(order.getCreatedAt(), Instant.now()).toMillis());
        }
        return executionEventAppendService.append(order, type, body);
    }
}
