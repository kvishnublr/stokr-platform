package com.stokr.execution.orphan.service;

import com.stokr.execution.orphan.domain.DetectedOrphanPosition;
import com.stokr.execution.orphan.domain.OrphanClassification;
import com.stokr.execution.orphan.repository.OrphanClassificationRepository;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.domain.StrategySignal;
import com.stokr.execution.orphan.dto.EvidenceScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanClassificationService {

    private final OrphanClassificationRepository classificationRepository;
    private final StrategySignalRepository signalRepository;
    private final OmsExecutionRepository executionRepository;
    private final EvidenceScoringService evidenceScoringService;
    private final AuditLogService auditLogService;

    public OrphanClassification classify(DetectedOrphanPosition orphan) {
        log.info("orphan.classification.started orphan_id={} symbol={}", orphan.getId(), orphan.getSymbol());

        StrategySignal matchedSignal = findMatchingSignal(orphan);
        OrphanClassification classification = new OrphanClassification();
        classification.setId(UUID.randomUUID());
        classification.setCreatedAt(OffsetDateTime.now());
        classification.setUpdatedAt(OffsetDateTime.now());
        classification.setVersion(0L);
        classification.setOrphanId(orphan.getId());

        if (matchedSignal != null) {
            classifyWithSignal(orphan, matchedSignal, classification);
        } else {
            classifyWithoutSignal(orphan, classification);
        }

        classificationRepository.save(classification);
        auditLogService.logClassificationRun(classification);

        log.info("orphan.classification.completed orphan_id={} type={} score={} status={}",
                orphan.getId(), classification.getClassificationType(), classification.getEvidenceScore(),
                classification.getStatus());

        return classification;
    }

    private void classifyWithSignal(DetectedOrphanPosition orphan, StrategySignal signal,
                                   OrphanClassification classification) {
        // Check for OMS order and execution
        boolean hasOmsOrder = checkForOmsOrder(orphan.getId());
        boolean hasExecution = checkForExecution(orphan.getId());

        if (hasOmsOrder && hasExecution) {
            classifyAsSystemManaged(orphan, signal, classification);
        } else if (isStrongSignalMatch(orphan, signal)) {
            classifyAsUnknownOriginWithSignal(orphan, signal, classification);
        } else {
            classifyAsUnknownOrigin(orphan, classification);
        }
    }

    private void classifyWithoutSignal(DetectedOrphanPosition orphan, OrphanClassification classification) {
        classifyAsUnknownOrigin(orphan, classification);
    }

    private void classifyAsSystemManaged(DetectedOrphanPosition orphan, StrategySignal signal,
                                        OrphanClassification classification) {
        EvidenceScore score = evidenceScoringService.calculateScore(orphan, signal);
        classification.setClassificationType("SYSTEM_MANAGED");
        classification.setConfidenceLevel("PROVEN");
        classification.setEvidenceScore(score.getScore());
        classification.setMatchedSignalId(signal.getId());
        classification.setSignalConfidence(85);
        classification.setClassificationReason("OMS order and execution records found with matching signal");
        classification.setStatus("PENDING_REVIEW");
    }

    private void classifyAsUnknownOriginWithSignal(DetectedOrphanPosition orphan, StrategySignal signal,
                                                   OrphanClassification classification) {
        EvidenceScore score = evidenceScoringService.calculateScore(orphan, signal);
        classification.setClassificationType("UNKNOWN_ORIGIN");
        classification.setConfidenceLevel("INFERRED");
        classification.setEvidenceScore(score.getScore());
        classification.setMatchedSignalId(signal.getId());
        classification.setSignalConfidence(score.getScore());

        // Check for contradictory evidence
        if (hasContradictoryEvidence(orphan, signal)) {
            classification.setStatus("DO_NOT_TOUCH");
            classification.setClassificationReason("Signal match found but OMS order missing and contradictory evidence detected - flagged as DO_NOT_TOUCH");
        } else {
            classification.setStatus("PENDING_REVIEW");
            classification.setClassificationReason("Signal found but OMS order missing - likely data loss");
        }
    }

    private void classifyAsUnknownOrigin(DetectedOrphanPosition orphan, OrphanClassification classification) {
        classification.setClassificationType("UNKNOWN_ORIGIN");
        classification.setConfidenceLevel("UNKNOWN");
        classification.setEvidenceScore(0);
        classification.setStatus("PENDING_REVIEW");
        classification.setClassificationReason("No matching signal found - origin cannot be determined");
    }

    private StrategySignal findMatchingSignal(DetectedOrphanPosition orphan) {
        OffsetDateTime from = orphan.getBrokerEntryTime().minusMinutes(5);
        OffsetDateTime to = orphan.getBrokerEntryTime().plusMinutes(5);

        return signalRepository.findSignalsInTimeWindow(orphan.getUserId(), orphan.getSymbol(), from, to)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private boolean isStrongSignalMatch(DetectedOrphanPosition orphan, StrategySignal signal) {
        if (signal == null) return false;

        Duration timeDiff = Duration.between(signal.getCreatedAt(), orphan.getBrokerEntryTime()).abs();
        if (timeDiff.toMinutes() > 30) return false;

        return true;
    }

    private boolean hasContradictoryEvidence(DetectedOrphanPosition orphan, StrategySignal signal) {
        // Check for large timestamp gaps
        Duration gap = Duration.between(signal.getCreatedAt(), orphan.getBrokerEntryTime()).abs();
        if (gap.toHours() > 2) return true;

        // Check for quantity mismatches
        if (signal.getQuantity() != null) {
            java.math.BigDecimal diff = orphan.getQuantity()
                    .subtract(new java.math.BigDecimal(signal.getQuantity()))
                    .abs();
            if (diff.signum() > 0) return true;
        }

        return false;
    }

    private boolean checkForOmsOrder(UUID orphanId) {
        // Implementation would check oms_order table
        return false;
    }

    private boolean checkForExecution(UUID orphanId) {
        // Implementation would check oms_execution table
        return false;
    }
}
