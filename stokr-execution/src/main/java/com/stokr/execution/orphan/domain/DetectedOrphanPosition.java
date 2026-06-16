package com.stokr.execution.orphan.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "orphan_detected_positions", indexes = {
    @Index(name = "idx_orphan_user_symbol", columnList = "user_id,symbol"),
    @Index(name = "idx_orphan_detected_time", columnList = "detection_timestamp"),
    @Index(name = "idx_orphan_status", columnList = "status"),
    @Index(name = "idx_orphan_do_not_touch", columnList = "status")
})
public class DetectedOrphanPosition extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "quantity", nullable = false, precision = 24, scale = 8)
    private BigDecimal quantity;

    @Column(name = "broker_entry_time", nullable = false)
    private Instant brokerEntryTime;

    @Column(name = "broker_entry_price", precision = 24, scale = 8)
    private BigDecimal brokerEntryPrice;

    @Column(name = "current_value", precision = 24, scale = 8)
    private BigDecimal currentValue;

    @Column(name = "detection_timestamp", nullable = false)
    private Instant detectionTimestamp;

    @Column(name = "detection_source", length = 64)
    private String detectionSource;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
