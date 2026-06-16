package com.stokr.trading.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "strategy_instances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "strategy_id", nullable = false)
    private UUID strategyId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "broker_account_id")
    private UUID brokerAccountId;

    @Column(length = 255)
    private String name;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "execution_mode", length = 20)
    @Builder.Default
    private String executionMode = "PAPER";

    @Column(precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal allocation = BigDecimal.valueOf(10000);

    @Column(name = "max_position_size", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal maxPositionSize = BigDecimal.valueOf(1000);

    @Column(name = "risk_multiplier", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal riskMultiplier = BigDecimal.ONE;

    @Column(name = "max_daily_loss", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal maxDailyLoss = BigDecimal.valueOf(500);

    @Column(length = 20)
    @Builder.Default
    private String status = "STOPPED";

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "last_signal_at")
    private Instant lastSignalAt;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted")
    @Builder.Default
    private Boolean deleted = false;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isRunning() {
        return "RUNNING".equalsIgnoreCase(status);
    }

    public boolean isStopped() {
        return "STOPPED".equalsIgnoreCase(status);
    }

    public boolean isPaused() {
        return "PAUSED".equalsIgnoreCase(status);
    }

    public boolean isLive() {
        return "LIVE".equalsIgnoreCase(executionMode);
    }

    public boolean isPaper() {
        return "PAPER".equalsIgnoreCase(executionMode) || "SIMULATED".equalsIgnoreCase(executionMode);
    }
}
