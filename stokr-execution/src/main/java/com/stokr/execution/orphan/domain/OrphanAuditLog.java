package com.stokr.execution.orphan.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "orphan_audit_log", indexes = {
    @Index(name = "idx_audit_orphan", columnList = "orphan_id"),
    @Index(name = "idx_audit_type", columnList = "event_type"),
    @Index(name = "idx_audit_actor", columnList = "actor_id"),
    @Index(name = "idx_audit_date", columnList = "created_at"),
    @Index(name = "idx_audit_symbol", columnList = "symbol")
})
public class OrphanAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "orphan_id", nullable = false)
    private UUID orphanId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "event_source", length = 64)
    private String eventSource;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_type", length = 32)
    private String actorType;

    @Column(name = "event_details", columnDefinition = "TEXT")
    private String eventDetails;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "symbol", length = 64)
    private String symbol;

    @Column(name = "classification_type", length = 32)
    private String classificationType;

    @Column(name = "retention_date")
    private Instant retentionDate;
}
