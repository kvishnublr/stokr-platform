package com.stokr.intraday.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "a_plus_strategy_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class APlusStrategyConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean enabled = true;

    @Column(nullable = false)
    private Integer entryAiScoreMin = 80;  // Lowered from 85 to 80 for real data deployment

    @Column(nullable = false)
    private Integer exitAiScoreThreshold = 70;

    @Column(precision = 10, scale = 4, nullable = false)
    private BigDecimal hardSlPct = BigDecimal.valueOf(0.50);  // Tightened from 1.50% to 0.50% (50 bps) for real data deployment

    @Column(precision = 10, scale = 4, nullable = false)
    private BigDecimal hardTpPct = BigDecimal.valueOf(3.00);

    @Column(nullable = false)
    private Integer positionSizeQty = 1;

    @Column(length = 100)
    private String universeGroup = "NIFTY_100";

    @Column(nullable = false)
    private Integer scanIntervalSec = 30;

    @Column(nullable = false)
    private Integer maxConcurrentPositions = 5;

    @Column(columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean autoCloseMarketEnd = true;

    @Column(nullable = false)
    private Integer marketCloseHour = 15;

    @Column(nullable = false)
    private Integer marketCloseMinute = 30;

    @Column(columnDefinition = "uuid")
    private java.util.UUID traderId;

    @Column(length = 50)
    private String executionMode = "BOTH"; // LIVE, PAPER, or BOTH

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
