package com.stokr.execution.safety;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "oms_execution_dedupe_keys")
@Getter
@Setter
public class OmsExecutionDedupeKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_key", nullable = false, unique = true, length = 512)
    private String executionKey;

    @Column(name = "strategy_name", nullable = false, length = 128)
    private String strategyName;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(nullable = false, length = 8)
    private String direction;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
