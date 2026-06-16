package com.stokr.risk.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "risk_evaluation_traces")
public class RiskEvaluationTrace extends BaseEntity {

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "allowed", nullable = false)
    private boolean allowed;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "message", length = 1000)
    private String message;

    @Column(name = "trace_json", nullable = false, columnDefinition = "text")
    private String traceJson;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;
}
