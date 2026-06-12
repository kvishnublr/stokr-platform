package com.stokr.execution.orphan.service;

import com.stokr.execution.orphan.domain.*;
import com.stokr.execution.orphan.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorReviewWorkflowService {

    private final OrphanReviewTaskRepository reviewTaskRepository;
    private final OrphanReviewApprovalRepository approvalRepository;
    private final OrphanClassificationRepository classificationRepository;
    private final DetectedOrphanPositionRepository orphanRepository;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public OrphanReviewTask createReviewTask(OrphanClassification classification) {
        DetectedOrphanPosition orphan = orphanRepository.findById(classification.getOrphanId())
                .orElseThrow(() -> new IllegalArgumentException("Orphan not found"));

        OrphanReviewTask task = new OrphanReviewTask();
        task.setId(UUID.randomUUID());
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        task.setVersion(0L);
        task.setOrphanId(classification.getOrphanId());
        task.setClassificationId(classification.getId());
        task.setSymbol(orphan.getSymbol());
        task.setBrokerQuantity(orphan.getQuantity());
        task.setClassificationType(classification.getClassificationType());
        task.setEvidenceScore(classification.getEvidenceScore());
        task.setConfidenceLevel(classification.getConfidenceLevel());

        // Set status based on classification
        if ("DO_NOT_TOUCH".equals(classification.getStatus())) {
            task.setStatus("DO_NOT_TOUCH");
            task.setPriority("CRITICAL");
            task.setDoNotTouchReason("Contradictory evidence detected - careful review required");
        } else if (classification.getEvidenceScore() >= 85) {
            task.setStatus("PENDING_REVIEW");
            task.setPriority("HIGH");
        } else {
            task.setStatus("PENDING_REVIEW");
            task.setPriority("MEDIUM");
        }

        task.setDueDate(Instant.now().plusSeconds(24));

        reviewTaskRepository.save(task);
        auditLogService.logReviewTaskCreated(task);

        log.info("review.task.created task_id={} orphan_id={} priority={} status={}",
                task.getId(), orphan.getId(), task.getPriority(), task.getStatus());

        return task;
    }

    @Transactional
    public void assignReviewTask(UUID taskId, UUID operatorId) {
        OrphanReviewTask task = reviewTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));

        task.setAssignedOperatorId(operatorId);
        task.setUpdatedAt(Instant.now());

        reviewTaskRepository.save(task);
        log.info("review.task.assigned task_id={} operator_id={}", taskId, operatorId);
    }

    @Transactional
    public OrphanReviewApproval approveClassification(UUID taskId, UUID operatorId,
                                                      String classification, String notes) {
        OrphanReviewTask task = reviewTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));

        OrphanClassification currentClassification = classificationRepository.findById(task.getClassificationId())
                .orElseThrow(() -> new IllegalArgumentException("Classification not found"));

        // Create approval record
        OrphanReviewApproval approval = new OrphanReviewApproval();
        approval.setId(UUID.randomUUID());
        approval.setCreatedAt(Instant.now());
        approval.setOrphanId(task.getOrphanId());
        approval.setReviewTaskId(taskId);
        approval.setOperatorId(operatorId);
        approval.setApprovedClassification(classification);
        approval.setOperatorNotes(notes);
        approval.setDecisionTimestamp(Instant.now());
        approval.setClassificationBefore(currentClassification.getClassificationType());
        approval.setEvidenceScoreBefore(currentClassification.getEvidenceScore());
        approval.setClassificationAfter(classification);
        approval.setEvidenceScoreAfter(currentClassification.getEvidenceScore());

        approvalRepository.save(approval);

        // Update task
        task.setStatus("APPROVED");
        task.setCompletedAt(Instant.now());
        reviewTaskRepository.save(task);

        // Update orphan
        DetectedOrphanPosition orphan = orphanRepository.findById(task.getOrphanId())
                .orElseThrow();
        orphan.setStatus("REVIEWED");
        orphanRepository.save(orphan);

        // Audit log
        auditLogService.logReviewApproval(approval);

        log.info("review.approval.recorded task_id={} operator_id={} classification={}",
                taskId, operatorId, classification);

        return approval;
    }

    @Transactional
    public void rejectClassification(UUID taskId, UUID operatorId, String reason) {
        OrphanReviewTask task = reviewTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));

        task.setStatus("REJECTED");
        task.setCompletedAt(Instant.now());
        reviewTaskRepository.save(task);

        auditLogService.logReviewRejection(task.getOrphanId(), taskId, operatorId, reason);

        // Create new review task
        OrphanClassification classification = classificationRepository.findById(task.getClassificationId())
                .orElseThrow();
        createReviewTask(classification);

        log.info("review.rejection.recorded task_id={} operator_id={}", taskId, operatorId);
    }

    public List<OrphanReviewTask> getPendingReviewTasks(UUID operatorId) {
        if (operatorId == null) {
            return reviewTaskRepository.findAllPendingTasks();
        }
        return reviewTaskRepository.findPendingTasksForOperator(operatorId);
    }

    public Optional<OrphanReviewTask> getReviewTask(UUID taskId) {
        return reviewTaskRepository.findById(taskId);
    }
}
