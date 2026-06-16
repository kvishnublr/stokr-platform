package com.stokr.execution.safety;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrokerExecutionTelemetryService {

    private final BrokerExecutionTelemetryRepository repository;

    @Transactional
    public BrokerExecutionTelemetry beginSubmit(
            UUID orderId,
            UUID userId,
            String strategyName,
            String symbol,
            String executionMode,
            String brokerVendor) {
        Instant now = Instant.now();
        BrokerExecutionTelemetry row = repository.findByOrderId(orderId).orElseGet(BrokerExecutionTelemetry::new);
        row.setOrderId(orderId);
        row.setUserId(userId);
        row.setStrategyName(strategyName);
        row.setSymbol(symbol);
        row.setExecutionMode(executionMode);
        row.setBrokerVendor(brokerVendor);
        row.setSubmitTime(now);
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(now);
        }
        row.setUpdatedAt(now);
        return repository.save(row);
    }

    @Transactional
    public void recordAck(UUID orderId, String brokerOrderId, Instant ackTime) {
        repository.findByOrderId(orderId).ifPresent(row -> {
            row.setAckTime(ackTime);
            row.setBrokerOrderId(brokerOrderId);
            if (row.getSubmitTime() != null) {
                row.setAckLatencyMs(Duration.between(row.getSubmitTime(), ackTime).toMillis());
            }
            row.setUpdatedAt(Instant.now());
            repository.save(row);
        });
    }

    @Transactional
    public void recordRejection(UUID orderId, String reason) {
        repository.findByOrderId(orderId).ifPresent(row -> {
            row.setRejectionReason(truncate(reason, 512));
            row.setUpdatedAt(Instant.now());
            repository.save(row);
            log.error("oms.broker.telemetry.rejected orderId={} reason={}", orderId, reason);
        });
    }

    @Transactional
    public void recordFill(UUID orderId, Instant fillTime, boolean partial) {
        repository.findByOrderId(orderId).ifPresent(row -> {
            row.setFillTime(fillTime);
            if (row.getSubmitTime() != null) {
                row.setFillLatencyMs(Duration.between(row.getSubmitTime(), fillTime).toMillis());
            }
            if (partial) {
                row.setPartialFillCount(row.getPartialFillCount() + 1);
            }
            row.setUpdatedAt(Instant.now());
            repository.save(row);
        });
    }

    @Transactional
    public void recordCancel(UUID orderId, Instant cancelTime) {
        repository.findByOrderId(orderId).ifPresent(row -> {
            row.setCancelTime(cancelTime);
            if (row.getSubmitTime() != null) {
                row.setCancelLatencyMs(Duration.between(row.getSubmitTime(), cancelTime).toMillis());
            }
            row.setUpdatedAt(Instant.now());
            repository.save(row);
        });
    }

    public Double avgAckLatencyMsSince(Instant since) {
        Double avg = repository.avgAckLatencyMsSince(since);
        return avg != null ? avg : 0.0;
    }

    public long telemetryCountSince(Instant since) {
        return repository.countByCreatedAtAfter(since);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
