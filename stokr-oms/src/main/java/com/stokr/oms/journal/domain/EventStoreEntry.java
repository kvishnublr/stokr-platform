package com.stokr.oms.journal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only domain event row (immutable after insert).
 */
@Getter
@Setter
@Entity
@Table(name = "event_store")
public class EventStoreEntry {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "stream_type", nullable = false, length = 32)
    private String streamType;

    @Column(name = "stream_key", nullable = false, length = 256)
    private String streamKey;

    @Column(name = "sequence_num", nullable = false)
    private long sequenceNum;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "text")
    private String payloadJson;

    @Column(name = "payload_hash", nullable = false, length = 128)
    private String payloadHash;

    @Column(name = "chain_hash", nullable = false, length = 128)
    private String chainHash;

    @Column(name = "prev_chain_hash", length = 128)
    private String prevChainHash;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "symbol", length = 64)
    private String symbol;

    @Column(name = "strategy_key", length = 128)
    private String strategyKey;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "backtest_run_id")
    private UUID backtestRunId;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
