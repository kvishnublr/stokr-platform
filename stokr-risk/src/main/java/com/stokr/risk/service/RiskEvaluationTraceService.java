package com.stokr.risk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.risk.domain.RiskEvaluationTrace;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import com.stokr.risk.repository.RiskEvaluationTraceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists every synchronous risk evaluation for replayability and audits.
 */
@Service
@RequiredArgsConstructor
public class RiskEvaluationTraceService {

    private final RiskEvaluationTraceRepository traceRepository;
    private final RiskMetricsService riskMetricsService;
    private final ObjectMapper mapper =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Transactional
    public void record(RiskContext ctx, RiskDecision decision, java.util.UUID orderId) {
        RiskEvaluationTrace t = new RiskEvaluationTrace();
        t.setUserId(ctx.userId());
        t.setOrderId(orderId);
        t.setAllowed(decision.allowed());
        t.setReasonCode(decision.reasonCode());
        t.setMessage(decision.message());
        t.setCorrelationId(CorrelationIdHolder.get());
        t.setTraceJson(buildTraceJson(ctx, decision));
        traceRepository.save(t);
        if (!decision.allowed()) {
            riskMetricsService.recordRejection();
        }
    }

    private String buildTraceJson(RiskContext ctx, RiskDecision decision) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", ctx.userId().toString());
            m.put("orderId", ctx.order().getId().toString());
            m.put("symbol", ctx.order().getSymbol());
            m.put("qty", ctx.order().getQuantity());
            m.put("mode", ctx.order().getExecutionMode() != null ? ctx.order().getExecutionMode().name() : null);
            m.put("dayPnl", ctx.dayPnl());
            m.put("openPositionCount", ctx.openPositionCount());
            m.put("ordersCreatedLast60Seconds", ctx.ordersCreatedLast60Seconds());
            m.put("signalAtrToCloseRatio", ctx.signalAtrToCloseRatio());
            m.put("brokerCircuitHalt", ctx.brokerCircuitHalt());
            m.put("portfolioDrawdownPct", ctx.portfolioDrawdownPct());
            m.put("evalInstantMs", ctx.lastOrderEpochMs());
            m.put("allowed", decision.allowed());
            m.put("reasonCode", decision.reasonCode());
            m.put("message", decision.message());
            return mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"trace_json_failed\"}";
        }
    }
}
