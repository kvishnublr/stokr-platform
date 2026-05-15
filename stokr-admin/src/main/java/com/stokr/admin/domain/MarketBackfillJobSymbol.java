package com.stokr.admin.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "market_backfill_job_symbols")
public class MarketBackfillJobSymbol extends BaseEntity {

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private MarketBackfillSymbolStatus status;

    @Column(name = "candles_fetched", nullable = false)
    private long candlesFetched;

    @Column(name = "latest_candle_at")
    private Instant latestCandleAt;

    @Column(name = "gap_count", nullable = false)
    private int gapCount;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "message", length = 500)
    private String message;
}
