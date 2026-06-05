package io.stokr.oms.domain.entity;

import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "strategy_pause_state", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "strategy_name"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StrategyPauseState {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "strategy_name", nullable = false)
    private String strategyName;

    @Column(name = "current_state", nullable = false)
    private String currentState;
        // RUNNING, PAUSED, STOPPED, EXIT_ALL_PAUSED, KILLSWITCH_PAUSED

    @Column(name = "survives_restart")
    private Boolean survivesRestart = true;
        // If true, pause state persists across application restart

    @Column(name = "survives_deployment")
    private Boolean survivesDeployment = true;
        // If true, pause state persists across blue-green deployment

    @Column(name = "resume_at")
    private LocalDateTime resumeAt;

    @Column(name = "resume_condition", columnDefinition = "VARCHAR(512)")
    private String resumeCondition;
        // "manual_resume_required", "after_market_hours", specific condition

    @Column(name = "pause_reason", columnDefinition = "TEXT")
    private String pauseReason;

    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    @Column(name = "paused_by_user_id")
    private UUID pausedByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isDurable() {
        return survivesRestart && survivesDeployment;
    }
}
