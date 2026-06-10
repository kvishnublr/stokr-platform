package com.stokr.execution.orphan.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "orphan_review_tasks", indexes = {
    @Index(name = "idx_review_status", columnList = "status"),
    @Index(name = "idx_review_assigned", columnList = "assigned_operator_id"),
    @Index(name = "idx_review_due_date", columnList = "due_date"),
    @Index(name = "idx_review_priority", columnList = "priority"),
    @Index(name = "idx_review_do_not_touch", columnList = "status")
})
public class OrphanReviewTask extends BaseEntity {

    @Column(name = "orphan_id", nullable = false)
    private UUID orphanId;

    @Column(name = "classification_id", nullable = false)
    private UUID classificationId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "assigned_operator_id")
    private UUID assignedOperatorId;

    @Column(name = "priority", length = 32)
    private String priority;

    @Column(name = "due_date", nullable = false)
    private OffsetDateTime dueDate;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "broker_quantity", nullable = false, precision = 24, scale = 8)
    private BigDecimal brokerQuantity;

    @Column(name = "classification_type", nullable = false, length = 32)
    private String classificationType;

    @Column(name = "evidence_score", nullable = false)
    private Integer evidenceScore;

    @Column(name = "confidence_level", nullable = false, length = 32)
    private String confidenceLevel;

    @Column(name = "operator_notes", columnDefinition = "TEXT")
    private String operatorNotes;

    @Column(name = "do_not_touch_reason", columnDefinition = "TEXT")
    private String doNotTouchReason;
}
