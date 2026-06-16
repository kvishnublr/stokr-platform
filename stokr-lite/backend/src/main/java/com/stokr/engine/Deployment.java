package com.stokr.engine;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "deployments")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Deployment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    @Column(name = "broker_account_id", nullable = false)
    private Long brokerAccountId;

    @Column(nullable = false)
    @Builder.Default
    private String mode = "PAPER"; // PAPER or LIVE

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal capital = new BigDecimal("100000");

    @Column(nullable = false)
    @Builder.Default
    private String status = "ACTIVE"; // ACTIVE, STOPPED, PAUSED

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isLive() { return "LIVE".equals(mode); }
    public boolean isActive() { return "ACTIVE".equals(status); }
}
