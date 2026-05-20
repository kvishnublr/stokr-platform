package com.stokr.oms.reconciliation;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "reconciliation_events")
public class ReconciliationEvent extends BaseEntity {

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "broker_vendor", nullable = false, length = 32)
    private String brokerVendor;

    @Column(name = "symbol", length = 64)
    private String symbol;

    @Column(name = "discrepancy_type", nullable = false, length = 64)
    private String discrepancyType;

    @Column(name = "broker_qty", precision = 24, scale = 8)
    private BigDecimal brokerQty;

    @Column(name = "internal_qty", precision = 24, scale = 8)
    private BigDecimal internalQty;

    @Column(name = "delta", precision = 24, scale = 8)
    private BigDecimal delta;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "OPEN";

    @Column(name = "notes", length = 512)
    private String notes;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
