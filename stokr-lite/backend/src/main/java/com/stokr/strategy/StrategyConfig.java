package com.stokr.strategy;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "strategy_configs")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class StrategyConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_id", nullable = false, unique = true)
    private Long strategyId;

    @Column(name = "allocated_capital")
    @Builder.Default
    private BigDecimal allocatedCapital = new BigDecimal("100000");

    @Column(name = "max_positions")
    @Builder.Default
    private Integer maxPositions = 2;

    @Column(name = "max_trade_quantity")
    @Builder.Default
    private Integer maxTradeQuantity = 1;

    @Column(name = "force_fixed_qty")
    @Builder.Default
    private boolean forceFixedQty = true;

    @Column(name = "fixed_qty")
    @Builder.Default
    private Integer fixedQty = 1;

    @Column(name = "sizing_mode")
    @Builder.Default
    private String sizingMode = "FIXED_QUANTITY"; // FIXED_QUANTITY | FIXED_CAPITAL | RISK_BASED

    @Column(name = "max_capital_per_trade")
    @Builder.Default
    private BigDecimal maxCapitalPerTrade = new BigDecimal("50000");

    @Column(name = "max_risk_per_trade_pct")
    @Builder.Default
    private BigDecimal maxRiskPerTradePct = new BigDecimal("1.0");

    @Column(name = "daily_loss_limit")
    @Builder.Default
    private BigDecimal dailyLossLimit = new BigDecimal("5000");

    @Column(name = "cooldown_minutes")
    @Builder.Default
    private Integer cooldownMinutes = 15;

    @Column(name = "live_enabled")
    @Builder.Default
    private boolean liveEnabled = false;

    @Column(name = "paper_enabled")
    @Builder.Default
    private boolean paperEnabled = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
