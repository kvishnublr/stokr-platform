package com.stokr.intraday.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Reference data for NSE listed stocks
 * Used by setup detectors and ranking engines
 */
@Entity
@Table(name = "nse_stocks", indexes = {
        @Index(name = "idx_nse_stocks_sector", columnList = "sector"),
        @Index(name = "idx_nse_stocks_market_cap", columnList = "market_cap_millions")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NseStock {
    @Id
    private String stockId;

    @Column(nullable = false, length = 100)
    private String stockName;

    @Column(nullable = false, length = 50)
    private String sector;

    private Long marketCapMillions;
    private Long averageVolumeDaily;
    private Integer lotSize;
    private BigDecimal pricePrecision;

    // Previous day's OHLC
    private BigDecimal prevClose;
    private BigDecimal prevHigh;
    private BigDecimal prevLow;

    // 52-week range
    @Column(name = "week_52_high")
    private BigDecimal week52High;

    @Column(name = "week_52_low")
    private BigDecimal week52Low;

    @Column(columnDefinition = "TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP")
    private Instant createdAt;

    @Column(columnDefinition = "TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
