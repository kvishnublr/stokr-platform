package com.stokr.strategy.operational;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(
        name = "strategy_runtime_health",
        uniqueConstraints = @UniqueConstraint(columnNames = {"strategy_name", "session_date"})
)
public class StrategyRuntimeHealth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_name", nullable = false, length = 128)
    private String strategyName;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Column(name = "execution_mode", nullable = false, length = 16)
    private String executionMode;

    @Column(name = "scans_attempted", nullable = false)
    private long scansAttempted;

    @Column(name = "scans_blocked_integrity", nullable = false)
    private long scansBlockedIntegrity;

    @Column(name = "scans_blocked_feed", nullable = false)
    private long scansBlockedFeed;

    @Column(name = "signals_generated", nullable = false)
    private long signalsGenerated;

    @Column(name = "trades_opened", nullable = false)
    private long tradesOpened;

    @Column(name = "trades_closed", nullable = false)
    private long tradesClosed;

    @Column(name = "rejection_rate", precision = 8, scale = 4)
    private BigDecimal rejectionRate;

    @Column(name = "avg_hold_seconds")
    private Long avgHoldSeconds;

    @Column(name = "last_scan_time")
    private Instant lastScanTime;

    @Column(name = "last_signal_time")
    private Instant lastSignalTime;

    @Column(name = "last_rejection_reason", length = 256)
    private String lastRejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
