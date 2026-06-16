package com.stokr.admin.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "market_backfill_failures")
public class MarketBackfillFailure extends BaseEntity {

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "symbol", length = 64)
    private String symbol;

    @Column(name = "failure_code", nullable = false, length = 64)
    private String failureCode;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "retryable", nullable = false)
    private boolean retryable = true;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 1;

    @Column(name = "last_occurred_at", nullable = false)
    private Instant lastOccurredAt;
}
