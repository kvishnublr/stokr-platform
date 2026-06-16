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
@Table(name = "market_backfill_gaps")
public class MarketBackfillGap extends BaseEntity {

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "timeframe", nullable = false, length = 16)
    private String timeframe;

    @Column(name = "gap_start", nullable = false)
    private Instant gapStart;

    @Column(name = "gap_end", nullable = false)
    private Instant gapEnd;

    @Column(name = "missing_bars", nullable = false)
    private int missingBars;

    @Column(name = "repaired", nullable = false)
    private boolean repaired;

    @Column(name = "message", length = 500)
    private String message;
}
