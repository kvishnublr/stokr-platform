package com.stokr.execution.safety;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "oms_safety_blocked_orders")
@Getter
@Setter
public class OmsSafetyBlockedOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "signal_id")
    private UUID signalId;

    @Column(name = "strategy_name", length = 128)
    private String strategyName;

    @Column(length = 64)
    private String symbol;

    @Column(name = "requested_mode", length = 16)
    private String requestedMode;

    @Column(name = "effective_mode", length = 16)
    private String effectiveMode;

    @Column(name = "block_code", nullable = false, length = 64)
    private String blockCode;

    @Column(name = "block_message", length = 512)
    private String blockMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
