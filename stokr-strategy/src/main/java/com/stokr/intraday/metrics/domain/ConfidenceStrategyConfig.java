package com.stokr.intraday.metrics.domain;

import lombok.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "confidence_strategy_config", indexes = {
    @Index(name = "idx_config_trader", columnList = "trader_id"),
    @Index(name = "idx_config_enabled", columnList = "enabled"),
    @Index(name = "idx_config_threshold", columnList = "min_confidence_threshold")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfidenceStrategyConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID traderId;

    @Column(nullable = false, length = 128, unique = true)
    private String strategyName;  // CONFIDENCE_BASED_70, etc.

    @Column(name = "min_confidence_threshold", nullable = false)
    private Integer minConfidenceThreshold;  // 60, 70, 80, or 90

    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
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

    public void setMinConfidenceThreshold(Integer threshold) {
        if (threshold != null && (threshold == 60 || threshold == 70 ||
            threshold == 80 || threshold == 90)) {
            this.minConfidenceThreshold = threshold;
            this.strategyName = "CONFIDENCE_BASED_" + threshold;
        } else {
            throw new IllegalArgumentException(
                "Threshold must be 60, 70, 80, or 90. Got: " + threshold);
        }
    }

    @Override
    public String toString() {
        return String.format("Config(trader:%s, strategy:%s, threshold:%d, enabled:%s)",
            traderId, strategyName, minConfidenceThreshold, enabled);
    }
}
