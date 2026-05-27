package com.stokr.execution.safety;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "broker_execution_telemetry")
@Getter
@Setter
public class BrokerExecutionTelemetry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "strategy_name", length = 128)
    private String strategyName;

    @Column(length = 64)
    private String symbol;

    @Column(name = "execution_mode", length = 16)
    private String executionMode;

    @Column(name = "submit_time")
    private Instant submitTime;

    @Column(name = "ack_time")
    private Instant ackTime;

    @Column(name = "ack_latency_ms")
    private Long ackLatencyMs;

    @Column(name = "fill_time")
    private Instant fillTime;

    @Column(name = "fill_latency_ms")
    private Long fillLatencyMs;

    @Column(name = "cancel_time")
    private Instant cancelTime;

    @Column(name = "cancel_latency_ms")
    private Long cancelLatencyMs;

    @Column(name = "rejection_reason", length = 512)
    private String rejectionReason;

    @Column(name = "partial_fill_count", nullable = false)
    private int partialFillCount;

    @Column(name = "broker_order_id", length = 128)
    private String brokerOrderId;

    @Column(name = "broker_vendor", length = 32)
    private String brokerVendor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
