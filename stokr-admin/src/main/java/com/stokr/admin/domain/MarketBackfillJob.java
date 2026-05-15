package com.stokr.admin.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "market_backfill_jobs")
public class MarketBackfillJob extends BaseEntity {

    @Column(name = "requested_by_user_id")
    private UUID requestedByUserId;

    @Column(name = "broker_source", nullable = false, length = 32)
    private String brokerSource;

    @Column(name = "symbol_group", nullable = false, length = 32)
    private String symbolGroup;

    @Column(name = "timeframe", nullable = false, length = 16)
    private String timeframe;

    @Column(name = "range_start", nullable = false)
    private Instant rangeStart;

    @Column(name = "range_end", nullable = false)
    private Instant rangeEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private MarketBackfillJobStatus status;

    @Column(name = "total_symbols", nullable = false)
    private int totalSymbols;

    @Column(name = "processed_symbols", nullable = false)
    private int processedSymbols;

    @Column(name = "total_candles_fetched", nullable = false)
    private long totalCandlesFetched;

    @Column(name = "total_gaps", nullable = false)
    private int totalGaps;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "latest_candle_at")
    private Instant latestCandleAt;

    @Column(name = "throughput_candles_per_sec", precision = 24, scale = 6)
    private BigDecimal throughputCandlesPerSec;

    @Column(name = "repair_state", length = 24)
    private String repairState;

    @Column(name = "cancelled", nullable = false)
    private boolean cancelled;

    @Column(name = "message", length = 500)
    private String message;
}
