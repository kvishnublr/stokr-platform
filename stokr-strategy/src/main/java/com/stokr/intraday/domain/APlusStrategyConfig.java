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

    @Builder.Default
    @Column(columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean enabled = true;

    @Builder.Default
    @Column(nullable = false)
    private Integer entryAiScoreMin = 80;  // Lowered from 85 to 80 for real data deployment

    @Builder.Default
    @Column(nullable = false)
    private Integer exitAiScoreThreshold = 70;

    @Builder.Default
    @Column(precision = 10, scale = 4, nullable = false)
    private BigDecimal hardSlPct = BigDecimal.valueOf(0.50);  // Tightened from 1.50% to 0.50% (50 bps) for real data deployment

    @Builder.Default
    @Column(precision = 10, scale = 4, nullable = false)
    private BigDecimal hardTpPct = BigDecimal.valueOf(3.00);

    @Builder.Default
    @Column(nullable = false)
    private Integer positionSizeQty = 1;

    @Builder.Default
    @Column(length = 100)
    private String universeGroup = "NIFTY_100";

    @Builder.Default
    @Column(nullable = false)
    private Integer scanIntervalSec = 30;

    @Builder.Default
    @Column(nullable = false)
    private Integer maxConcurrentPositions = 5;

    @Builder.Default
    @Column(columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean autoCloseMarketEnd = true;

    @Builder.Default
    @Column(nullable = false)
    private Integer marketCloseHour = 15;

    @Builder.Default
    @Column(nullable = false)
    private Integer marketCloseMinute = 30;

    @Column(columnDefinition = "uuid")
    private java.util.UUID traderId;

    @Builder.Default
    @Column(length = 50)
    private String executionMode = "BOTH"; // LIVE, PAPER, or BOTH

    @Builder.Default
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
