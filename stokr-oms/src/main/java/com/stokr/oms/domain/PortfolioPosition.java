package com.stokr.oms.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "portfolio_positions")
public class PortfolioPosition extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "quantity", nullable = false, precision = 24, scale = 8)
    private BigDecimal quantity;

    @Column(name = "avg_price", precision = 24, scale = 8)
    private BigDecimal avgPrice;

    @Column(name = "realized_pnl", precision = 24, scale = 8)
    private BigDecimal realizedPnl;

    @Column(name = "unrealized_pnl", precision = 24, scale = 8)
    private BigDecimal unrealizedPnl;

    @Column(name = "mtm_price", precision = 24, scale = 8)
    private BigDecimal mtmPrice;

    @Column(name = "strategy_key", length = 128)
    private String strategyKey;

    @Column(name = "is_simulation", nullable = false)
    private boolean simulation;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.UUID)
    @Column(name = "simulation_run_id")
    private java.util.UUID simulationRunId;

    @Column(name = "simulation_scenario", length = 64)
    private String simulationScenario;
}
