package com.stokr.execution.orphan.service;

import com.stokr.execution.orphan.domain.DetectedOrphanPosition;
import com.stokr.execution.orphan.domain.OrphanClassification;
import com.stokr.execution.orphan.repository.OrphanClassificationRepository;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.UUID;

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

        OrphanClassification classification = new OrphanClassification();
        classification.setId(UUID.randomUUID());
        classification.setCreatedAt(java.time.Instant.now());
        classification.setUpdatedAt(java.time.Instant.now());
        classification.setVersion(0L);
        classification.setOrphanId(orphan.getId());

        // Placeholder: Default to UNKNOWN_ORIGIN classification
        // Detailed classification logic TBD based on evidence scoring
        classification.setClassificationType("UNKNOWN_ORIGIN");
        classification.setConfidenceLevel("UNKNOWN");
        classification.setEvidenceScore(0);
        classification.setStatus("PENDING_REVIEW");
        classification.setClassificationReason("Phase 1: Placeholder classification - full evidence scoring TBD");

        classificationRepository.save(classification);
        auditLogService.logClassificationRun(classification);

        log.info("orphan.classification.completed orphan_id={} type={} score={} status={}",
                orphan.getId(), classification.getClassificationType(), classification.getEvidenceScore(),
                classification.getStatus());

        return classification;
    }
}
