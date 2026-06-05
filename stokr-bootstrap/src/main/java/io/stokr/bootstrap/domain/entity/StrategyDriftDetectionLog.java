package io.stokr.bootstrap.domain.entity;

import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "strategy_drift_detection_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StrategyDriftDetectionLog {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    private UUID id;

    @Column(name = "check_time")
    private LocalDateTime checkTime;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "strategy_name")
    private String strategyName;

    @Column(name = "expected_entry_conditions_met")
    private Boolean expectedEntryConditionsMet;

    @Column(name = "actual_entry_conditions_met")
    private Boolean actualEntryConditionsMet;

    @Column(name = "entry_drift_detected")
    private Boolean entryDriftDetected;

    @Column(name = "expected_exit_conditions_met")
    private Boolean expectedExitConditionsMet;

    @Column(name = "actual_exit_conditions_met")
    private Boolean actualExitConditionsMet;

    @Column(name = "exit_drift_detected")
    private Boolean exitDriftDetected;

    @Column(name = "expected_open_positions")
    private Integer expectedOpenPositions;

    @Column(name = "actual_open_positions")
    private Integer actualOpenPositions;

    @Column(name = "position_delta")
    private Integer positionDelta;

    @Column(name = "drift_severity")
    private String driftSeverity;  // LOW, MEDIUM, HIGH

    @Column(name = "alert_sent")
    private Boolean alertSent;

    @Column(name = "strategy_paused")
    private Boolean strategyPaused;

    @Column(name = "pause_reason")
    private String pauseReason;
}
