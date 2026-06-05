package io.stokr.bootstrap.domain.entity;

import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "strategy_definition", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "strategy_name"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyDefinition {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "strategy_name")
    private String strategyName;

    @Column(name = "strategy_type")
    private String strategyType;

    @Column(name = "entry_trigger")
    private String entryTriggerId;

    @Column(name = "entry_criteria_description", columnDefinition = "TEXT")
    private String entryCriteriaDescription;

    @Column(name = "entry_validation_rule", columnDefinition = "TEXT")
    private String entryValidationRule;

    @Column(name = "exit_trigger")
    private String exitTriggerId;

    @Column(name = "exit_criteria_description", columnDefinition = "TEXT")
    private String exitCriteriaDescription;

    @Column(name = "exit_validation_rule", columnDefinition = "TEXT")
    private String exitValidationRule;

    @Column(name = "has_target_exit")
    private Boolean hasTargetExit;

    @Column(name = "target_exit_rule", columnDefinition = "TEXT")
    private String targetExitRule;

    @Column(name = "has_stoploss_exit")
    private Boolean hasStoplossExit;

    @Column(name = "stoploss_exit_rule", columnDefinition = "TEXT")
    private String stoplossExitRule;

    @Column(name = "has_trailing_exit")
    private Boolean hasTrailingExit;

    @Column(name = "trailing_exit_rule", columnDefinition = "TEXT")
    private String trailingExitRule;

    @Column(name = "has_time_exit")
    private Boolean hasTimeExit;

    @Column(name = "time_exit_rule", columnDefinition = "TEXT")
    private String timeExitRule;

    @Column(name = "has_pressure_exit")
    private Boolean hasPressureExit;

    @Column(name = "pressure_exit_rule", columnDefinition = "TEXT")
    private String pressureExitRule;

    @Column(name = "suggested_tuning_parameters", columnDefinition = "TEXT")
    private String suggestedTuningParameters;

    @Column(name = "max_position_size")
    private BigDecimal maxPositionSize;

    @Column(name = "max_daily_loss")
    private BigDecimal maxDailyLoss;

    @Column(name = "risk_per_trade_percent")
    private BigDecimal riskPerTradePercent;

    @Column(name = "is_documented")
    private Boolean isDocumented;

    @Column(name = "is_validated")
    private Boolean isValidated;

    @Column(name = "compliance_review_status")
    private String complianceReviewStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_modified_at")
    private LocalDateTime lastModifiedAt;

    @Column(name = "compliance_reviewed_at")
    private LocalDateTime complianceReviewedAt;

    @Column(name = "reviewed_by_user_id")
    private UUID reviewedByUserId;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (lastModifiedAt == null) {
            lastModifiedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        lastModifiedAt = LocalDateTime.now();
    }
}
