package com.stokr.execution.orphan.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "orphan_classification_results", indexes = {
    @Index(name = "idx_classification_orphan", columnList = "orphan_id"),
    @Index(name = "idx_classification_type", columnList = "classification_type"),
    @Index(name = "idx_classification_score", columnList = "evidence_score"),
    @Index(name = "idx_classification_status", columnList = "status"),
    @Index(name = "idx_classification_do_not_touch", columnList = "status")
})
public class OrphanClassification extends BaseEntity {

    @Column(name = "orphan_id", nullable = false)
    private UUID orphanId;

    @Column(name = "classification_type", nullable = false, length = 32)
    private String classificationType;

    @Column(name = "confidence_level", nullable = false, length = 32)
    private String confidenceLevel;

    @Column(name = "evidence_score", nullable = false)
    private Integer evidenceScore;

    @Column(name = "supporting_evidence", columnDefinition = "TEXT")
    private String supportingEvidence;

    @Column(name = "blocking_issues", columnDefinition = "TEXT")
    private String blockingIssues;

    @Column(name = "matched_signal_id")
    private UUID matchedSignalId;

    @Column(name = "signal_confidence")
    private Integer signalConfidence;

    @Column(name = "broker_entry_source_found")
    private Boolean brokerEntrySourceFound;

    @Column(name = "broker_entry_source_value", length = 128)
    private String brokerEntrySourceValue;

    @Column(name = "evidence_breakdown", columnDefinition = "TEXT")
    private String evidenceBreakdown;

    @Column(name = "classification_reason", columnDefinition = "TEXT")
    private String classificationReason;

    @Column(name = "status", nullable = false, length = 32)
    private String status;
}
