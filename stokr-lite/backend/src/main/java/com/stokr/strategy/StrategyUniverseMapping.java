package com.stokr.strategy;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "strategy_universe_mappings")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class StrategyUniverseMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    @Column(name = "universe_group_id", nullable = false)
    private Long universeGroupId;

    @Column(name = "runtime_enabled")
    @Builder.Default
    private boolean runtimeEnabled = true;

    @Column(name = "max_positions")
    @Builder.Default
    private Integer maxPositions = 2;

    @Column(name = "capital_limit")
    @Builder.Default
    private BigDecimal capitalLimit = new BigDecimal("100000");

    @Column(name = "risk_profile")
    @Builder.Default
    private String riskProfile = "MEDIUM"; // LOW | MEDIUM | HIGH

    @Column(name = "scan_interval_seconds")
    @Builder.Default
    private Integer scanIntervalSeconds = 60;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
