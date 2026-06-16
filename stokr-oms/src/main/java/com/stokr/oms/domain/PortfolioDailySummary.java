package com.stokr.oms.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "portfolio_daily_summary")
public class PortfolioDailySummary extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "realized_pnl", nullable = false, precision = 24, scale = 8)
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    @Column(name = "unrealized_pnl", nullable = false, precision = 24, scale = 8)
    private BigDecimal unrealizedPnl = BigDecimal.ZERO;

    @Column(name = "mtm_pnl", nullable = false, precision = 24, scale = 8)
    private BigDecimal mtmPnl = BigDecimal.ZERO;

    @Column(name = "cumulative_pnl", nullable = false, precision = 24, scale = 8)
    private BigDecimal cumulativePnl = BigDecimal.ZERO;
}
