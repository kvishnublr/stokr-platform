package io.stokr.oms.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.GenericGenerator;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "position_lifecycle_audit")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PositionLifecycleAudit {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    private UUID id;

    @Column(name = "position_id", nullable = false)
    private UUID positionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "old_state")
    private String oldState;

    @Column(name = "new_state", nullable = false)
    private String newState;

    @Column(name = "owner_type")
    private String ownerType;

    @Column(name = "exit_source")
    private String exitSource;

    @Column(name = "triggered_by")
    private String triggeredBy;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "old_quantity")
    private BigDecimal oldQuantity;

    @Column(name = "new_quantity")
    private BigDecimal newQuantity;

    @Column(name = "change_amount")
    private BigDecimal changeAmount;

    @Column(name = "entry_signal_id")
    private UUID entrySignalId;

    @Column(name = "exit_signal_id")
    private UUID exitSignalId;

    @Column(name = "entry_order_id")
    private UUID entryOrderId;

    @Column(name = "exit_order_id")
    private UUID exitOrderId;

    @Column(name = "entry_execution_id")
    private UUID entryExecutionId;

    @Column(name = "exit_execution_id")
    private UUID exitExecutionId;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @PrePersist
    public void prePersist() {
        if (recordedAt == null) {
            recordedAt = LocalDateTime.now();
        }
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
    }
}
