package com.stokr.oms.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "portfolio_pnl_snapshots")
public class PortfolioPnlSnapshot extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "as_of", nullable = false)
    private Instant asOf;

    @Column(name = "pnl", nullable = false, precision = 24, scale = 8)
    private BigDecimal pnl = BigDecimal.ZERO;

    @Column(name = "realized_pnl", precision = 24, scale = 8)
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    @Column(name = "unrealized_pnl", precision = 24, scale = 8)
    private BigDecimal unrealizedPnl = BigDecimal.ZERO;

    @Column(name = "mtm_pnl", precision = 24, scale = 8)
    private BigDecimal mtmPnl = BigDecimal.ZERO;
}
