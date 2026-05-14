package com.stokr.oms.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "oms_execution_events")
public class OmsExecutionEvent extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private OmsOrder order;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "signal_id")
    private UUID signalId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "backtest_run_id")
    private UUID backtestRunId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private ExecutionEventType eventType;

    @Column(name = "event_payload_json", nullable = false, columnDefinition = "text")
    private String eventPayloadJson;

    @Column(name = "stream_sequence")
    private Long streamSequence;
}
