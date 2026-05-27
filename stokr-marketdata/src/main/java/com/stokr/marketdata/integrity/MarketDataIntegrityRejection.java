package com.stokr.marketdata.integrity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "market_data_integrity_rejections")
public class MarketDataIntegrityRejection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_name", nullable = false, length = 128)
    private String strategyName;

    @Column(name = "symbol", length = 64)
    private String symbol;

    @Column(name = "rejection_reason", nullable = false, length = 128)
    private String rejectionReason;

    @Column(name = "latest_bar_time")
    private Instant latestBarTime;

    @Column(name = "expected_bar_time")
    private Instant expectedBarTime;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
