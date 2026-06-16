package com.stokr.strategy.pipeline;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "signal_pipeline_audit")
@Getter
@Setter
public class SignalPipelineAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "strategy_key", nullable = false, length = 128)
    private String strategyKey;

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "signal_id")
    private UUID signalId;

    @Column(name = "pipeline_stage", nullable = false, length = 32)
    private String pipelineStage;

    @Column(name = "execution_status", nullable = false, length = 32)
    private String executionStatus;

    @Column(name = "rejection_code", length = 64)
    private String rejectionCode;

    @Column(name = "rejection_message", length = 512)
    private String rejectionMessage;

    @Column(name = "requested_mode", length = 16)
    private String requestedMode;

    @Column(name = "effective_mode", length = 16)
    private String effectiveMode;

    @Column(name = "confidence_score", precision = 10, scale = 6)
    private BigDecimal confidenceScore;

    @Column(name = "quality_gate", length = 32)
    private String qualityGate;

    @Column(name = "risk_gate", length = 32)
    private String riskGate;

    @Column(name = "cooldown_sec_remaining")
    private Integer cooldownSecRemaining;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
