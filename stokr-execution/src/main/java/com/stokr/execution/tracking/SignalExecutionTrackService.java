package com.stokr.execution.tracking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.strategy.domain.StrategySignalEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SignalExecutionTrackService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${stokr.signal-execution-tracks.enabled:true}")
    private boolean enabled;

    public void recordOrderStage(
            OmsOrder order,
            String stage,
            String status,
            String reason,
            Map<String, Object> metadata
    ) {
        if (order == null) {
            return;
        }
        insert(
                order.getSignalId(),
                order.getUserId(),
                order.getId(),
                order.getStrategyKey(),
                order.getSymbol(),
                order.getExecutionMode() != null ? order.getExecutionMode().name() : null,
                order.getState() != null ? order.getState().name() : null,
                stage,
                status,
                reason,
                metadata
        );
    }

    public void recordSignalStage(
            StrategySignalEntity signal,
            UUID userId,
            String stage,
            String status,
            String reason,
            Map<String, Object> metadata
    ) {
        if (signal == null) {
            return;
        }
        insert(
                signal.getId(),
                userId != null ? userId : signal.getUserId(),
                null,
                signal.getStrategyName(),
                signal.getSymbol(),
                null,
                null,
                stage,
                status,
                reason,
                metadata
        );
    }

    private void insert(
            UUID signalId,
            UUID userId,
            UUID orderId,
            String strategyKey,
            String symbol,
            String executionMode,
            String orderState,
            String stage,
            String status,
            String reason,
            Map<String, Object> metadata
    ) {
        if (!enabled || signalId == null) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO signal_execution_tracks (
                        id, created_at, updated_at, version, deleted,
                        signal_id, user_id, order_id, strategy_key, symbol,
                        execution_mode, order_state, stage, status, reason,
                        event_time, metadata
                    )
                    VALUES (?, now(), now(), 0, false, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """,
                    UUID.randomUUID(),
                    signalId,
                    userId,
                    orderId,
                    trim(strategyKey, 128),
                    trim(symbol, 64),
                    trim(executionMode, 16),
                    trim(orderState, 64),
                    trim(stage, 64),
                    trim(status, 32),
                    trim(reason, 512),
                    Instant.now(),
                    toJson(metadata));
        } catch (DataAccessException ex) {
            log.debug("signal_execution_track.write_failed signalId={} stage={} reason={}",
                    signalId, stage, ex.getMessage());
        }
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            return "{\"error\":\"metadata_serialization_failed\"}";
        }
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
