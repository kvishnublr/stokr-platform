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
@Table(name = "manual_exit_suppression", uniqueConstraints = {
    @UniqueConstraint(columnNames = "position_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ManualExitSuppression {

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

    @Column(name = "suppress_sl_exit")
    private Boolean suppressSlExit = true;

    @Column(name = "suppress_target_exit")
    private Boolean suppressTargetExit = true;

    @Column(name = "suppress_pressure_exit")
    private Boolean suppressPressureExit = true;

    @Column(name = "suppress_feed_protection_exit")
    private Boolean suppressFeedProtectionExit = true;

    @Column(name = "suppress_auto_exit")
    private Boolean suppressAutoExit = true;

    @Column(name = "suppress_all_exits")
    private Boolean suppressAllExits = true;

    @Column(name = "manual_exit_source")
    private String manualExitSource;

    @Column(name = "manual_exit_time")
    private LocalDateTime manualExitTime;

    @Column(name = "manual_exit_quantity")
    private BigDecimal manualExitQuantity;

    @Column(name = "manual_exit_price")
    private BigDecimal manualExitPrice;

    @Column(name = "manual_exit_reference")
    private String manualExitReference;

    @Column(name = "is_partial_exit")
    private Boolean isPartialExit;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

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

    public boolean isAnyExitAllowed() {
        return !suppressAllExits && (!suppressSlExit || !suppressTargetExit
            || !suppressPressureExit || !suppressFeedProtectionExit);
    }
}
