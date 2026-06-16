package com.stokr.marketdata.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "market_data_coverage")
public class MarketDataCoverage extends BaseEntity {

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "timeframe", nullable = false, length = 16)
    private String timeframe;

    @Column(name = "covered_from", nullable = false)
    private Instant coveredFrom;

    @Column(name = "covered_to", nullable = false)
    private Instant coveredTo;

    @Column(name = "coverage_start")
    private Instant coverageStart;

    @Column(name = "coverage_end")
    private Instant coverageEnd;

    @Column(name = "latest_candle_at")
    private Instant latestCandleAt;

    @Column(name = "freshness_age_seconds")
    private Long freshnessAgeSeconds;

    @Column(name = "completeness", nullable = false, length = 24)
    private String completeness;

    @Column(name = "freshness", nullable = false, length = 24)
    private String freshness;

    @Column(name = "gaps_present", nullable = false)
    private boolean gapsPresent;

    @Column(name = "gap_count", nullable = false)
    private int gapCount;

    @Column(name = "replay_readiness", nullable = false, length = 32)
    private String replayReadiness;

    @Column(name = "scanner_readiness", nullable = false, length = 32)
    private String scannerReadiness;

    @Column(name = "completion_pct", precision = 12, scale = 4)
    private BigDecimal completionPct;

    @Column(name = "last_validation_at", nullable = false)
    private Instant lastValidationAt;

    @Column(name = "validated_range_start")
    private Instant validatedRangeStart;

    @Column(name = "validated_range_end")
    private Instant validatedRangeEnd;

    @Column(name = "replay_ready", nullable = false)
    private boolean replayReady;

    @Column(name = "scanner_ready", nullable = false)
    private boolean scannerReady;

    @Column(name = "partial_coverage", nullable = false)
    private boolean partialCoverage;

    @Column(name = "stale_state", nullable = false, length = 24)
    private String staleState;

    @Column(name = "note", length = 500)
    private String note;
}
