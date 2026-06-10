package com.stokr.execution.orphan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.execution.orphan.domain.*;
import com.stokr.execution.orphan.repository.OrphanAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final OrphanAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public void logOrphanDetected(DetectedOrphanPosition orphan) {
        Map<String, Object> details = new HashMap<>();
        details.put("broker_qty", orphan.getQuantity());
        details.put("broker_entry_price", orphan.getBrokerEntryPrice());
        details.put("broker_entry_time", orphan.getBrokerEntryTime());
        details.put("detection_source", orphan.getDetectionSource());

        logEvent(orphan.getId(), "ORPHAN_DETECTED", "SCHEDULER", null, "SYSTEM_SERVICE",
                 orphan.getUserId(), orphan.getSymbol(), null, details);
    }

    public void logClassificationRun(OrphanClassification classification) {
        Map<String, Object> details = new HashMap<>();
        details.put("classification_type", classification.getClassificationType());
        details.put("confidence_level", classification.getConfidenceLevel());
        details.put("evidence_score", classification.getEvidenceScore());
        details.put("matched_signal_id", classification.getMatchedSignalId());
        details.put("blocking_issues", classification.getBlockingIssues());
        details.put("do_not_touch", "DO_NOT_TOUCH".equals(classification.getStatus()));

        logEvent(classification.getOrphanId(), "CLASSIFICATION_RUN", "SYSTEM", null, "SYSTEM_SERVICE",
                 null, null, classification.getClassificationType(), details);
    }

    public void logReviewTaskCreated(OrphanReviewTask task) {
        Map<String, Object> details = new HashMap<>();
        details.put("task_id", task.getId());
        details.put("assigned_to", task.getAssignedOperatorId());
        details.put("due_date", task.getDueDate());
        details.put("status", task.getStatus());
        details.put("do_not_touch_reason", task.getDoNotTouchReason());

        logEvent(task.getOrphanId(), "REVIEW_TASK_CREATED", "SYSTEM", null, "SYSTEM_SERVICE",
                 null, task.getSymbol(), task.getClassificationType(), details);
    }

    public void logReviewApproval(OrphanReviewApproval approval) {
        Map<String, Object> details = new HashMap<>();
        details.put("operator_id", approval.getOperatorId());
        details.put("approved_classification", approval.getApprovedClassification());
        details.put("classification_before", approval.getClassificationBefore());
        details.put("evidence_score_before", approval.getEvidenceScoreBefore());
        details.put("operator_notes", approval.getOperatorNotes());

        logEvent(approval.getOrphanId(), "OPERATOR_APPROVAL", "OPERATOR", approval.getOperatorId(), "OPERATOR",
                 null, null, approval.getApprovedClassification(), details);
    }

    public void logReviewRejection(UUID orphanId, UUID taskId, UUID operatorId, String reason) {
        Map<String, Object> details = new HashMap<>();
        details.put("task_id", taskId);
        details.put("reason", reason);

        logEvent(orphanId, "OPERATOR_REJECTION", "OPERATOR", operatorId, "OPERATOR",
                 null, null, null, details);
    }

    public List<OrphanAuditLog> getOrphanAuditLog(UUID orphanId) {
        return auditLogRepository.findByOrphanIdOrderByCreatedAtAsc(orphanId);
    }

    public List<OrphanAuditLog> getOperatorDecisions(UUID operatorId, Instant from, Instant to) {
        return auditLogRepository.findOperatorDecisions(operatorId, from, to);
    }

    private void logEvent(UUID orphanId, String eventType, String eventSource, UUID actorId, String actorType,
                         UUID userId, String symbol, String classificationType, Map<String, Object> details) {
        try {
            OrphanAuditLog log = new OrphanAuditLog();
            log.setCreatedAt(Instant.now());
            log.setOrphanId(orphanId);
            log.setEventType(eventType);
            log.setEventSource(eventSource);
            log.setActorId(actorId);
            log.setActorType(actorType);
            log.setUserId(userId);
            log.setSymbol(symbol);
            log.setClassificationType(classificationType);
            log.setEventDetails(objectMapper.writeValueAsString(details));
            log.setRetentionDate(Instant.now().plus(2));

            auditLogRepository.save(log);
            log.info("audit.logged event_type={} orphan_id={} actor_type={}", eventType, orphanId, actorType);
        } catch (Exception e) {
            log.error("audit.log.failed event_type={} orphan_id={}", eventType, orphanId, e);
        }
    }
}
