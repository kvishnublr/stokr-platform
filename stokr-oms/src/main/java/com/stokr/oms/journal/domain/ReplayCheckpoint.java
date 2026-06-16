package com.stokr.oms.journal.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "replay_checkpoints")
public class ReplayCheckpoint extends BaseEntity {

    @Column(name = "stream_type", nullable = false, length = 32)
    private String streamType;

    @Column(name = "stream_key", nullable = false, length = 256)
    private String streamKey;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    @Column(name = "checkpoint_hash", nullable = false, length = 128)
    private String checkpointHash;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "backtest_run_id")
    private UUID backtestRunId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "recovery_metadata", columnDefinition = "text")
    private String recoveryMetadata;
}
