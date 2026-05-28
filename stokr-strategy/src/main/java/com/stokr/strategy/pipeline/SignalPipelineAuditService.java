package com.stokr.strategy.pipeline;

import com.stokr.strategy.domain.StrategySignalEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SignalPipelineAuditService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final SignalPipelineAuditRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            UUID userId,
            String strategyKey,
            String symbol,
            UUID signalId,
            String pipelineStage,
            String executionStatus,
            String rejectionCode,
            String rejectionMessage,
            String requestedMode,
            String effectiveMode,
            BigDecimal confidenceScore,
            String qualityGate,
            String riskGate,
            Integer cooldownSecRemaining) {
        SignalPipelineAudit row = new SignalPipelineAudit();
        row.setUserId(userId);
        row.setStrategyKey(strategyKey != null ? strategyKey : "UNKNOWN");
        row.setSymbol(symbol != null ? symbol : "UNKNOWN");
        row.setSignalId(signalId);
        row.setPipelineStage(pipelineStage != null ? pipelineStage : "DETECTED");
        row.setExecutionStatus(executionStatus != null ? executionStatus : "BLOCKED");
        row.setRejectionCode(rejectionCode);
        row.setRejectionMessage(rejectionMessage);
        row.setRequestedMode(requestedMode);
        row.setEffectiveMode(effectiveMode);
        row.setConfidenceScore(confidenceScore);
        row.setQualityGate(qualityGate);
        row.setRiskGate(riskGate);
        row.setCooldownSecRemaining(cooldownSecRemaining);
        row.setCreatedAt(Instant.now());
        repository.save(row);
    }

    public void recordRejection(
            String strategyKey,
            String symbol,
            String pipelineStage,
            String executionStatus,
            String code,
            String message) {
        record(null, strategyKey, symbol, null, pipelineStage, executionStatus, code, message,
                null, null, null, null, null, null);
    }

    public void recordSignalDrop(StrategySignalEntity signal, String code, String message, String qualityGate) {
        if (signal == null) {
            return;
        }
        record(signal.getUserId(), signal.getStrategyName(), signal.getSymbol(), signal.getId(),
                "QUALITY_CHECK", "QUALITY_REJECTED", code, message,
                signal.getPipeline(), signal.getPipeline(), signal.getConfidenceScore(),
                qualityGate, null, null);
    }

    public List<SignalPipelineAudit> recentToday(UUID userId, int limit) {
        Instant since = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        if (userId != null) {
            return repository.findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                    userId, since, PageRequest.of(0, limit));
        }
        return repository.findByCreatedAtAfterOrderByCreatedAtDesc(since, PageRequest.of(0, limit));
    }
}
