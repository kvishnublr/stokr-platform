package com.stokr.execution.orphan.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "broker_position_observations", indexes = {
    @Index(name = "idx_obs_user_symbol_time", columnList = "user_id,symbol,observation_time"),
    @Index(name = "idx_obs_is_orphaned", columnList = "is_orphaned,observation_time"),
    @Index(name = "idx_obs_time", columnList = "observation_time")
})
public class BrokerPositionObservation extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "broker_quantity", nullable = false, precision = 24, scale = 8)
    private BigDecimal brokerQuantity;

    @Column(name = "broker_entry_price", precision = 24, scale = 8)
    private BigDecimal brokerEntryPrice;

    @Column(name = "broker_entry_time")
    private OffsetDateTime brokerEntryTime;

    @Column(name = "observation_time", nullable = false)
    private OffsetDateTime observationTime;

    @Column(name = "broker_order_id", length = 64)
    private String brokerOrderId;

    @Column(name = "broker_position_type", length = 32)
    private String brokerPositionType;

    @Column(name = "broker_entry_source", length = 128)
    private String brokerEntrySource;

    @Column(name = "broker_tags", columnDefinition = "TEXT")
    private String brokerTags;

    @Column(name = "is_orphaned")
    private Boolean isOrphaned;

    @Column(name = "matched_oms_order_id")
    private UUID matchedOmsOrderId;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
