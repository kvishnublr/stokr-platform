package com.stokr.execution.orphan.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "orphan_review_approvals", indexes = {
    @Index(name = "idx_approval_operator", columnList = "operator_id"),
    @Index(name = "idx_approval_date", columnList = "decision_timestamp"),
    @Index(name = "idx_approval_orphan", columnList = "orphan_id")
})
public class OrphanReviewApproval extends BaseEntity {

    @Column(name = "orphan_id", nullable = false)
    private UUID orphanId;

    @Column(name = "review_task_id", nullable = false)
    private UUID reviewTaskId;

    @Column(name = "operator_id", nullable = false)
    private UUID operatorId;

    @Column(name = "approved_classification", nullable = false, length = 32)
    private String approvedClassification;

    @Column(name = "operator_notes", columnDefinition = "TEXT")
    private String operatorNotes;

    @Column(name = "decision_timestamp", nullable = false)
    private OffsetDateTime decisionTimestamp;

    @Column(name = "classification_before", length = 32)
    private String classificationBefore;

    @Column(name = "evidence_score_before")
    private Integer evidenceScoreBefore;

    @Column(name = "classification_after", length = 32)
    private String classificationAfter;

    @Column(name = "evidence_score_after")
    private Integer evidenceScoreAfter;

    @Column(name = "approval_reason", columnDefinition = "TEXT")
    private String approvalReason;
}
