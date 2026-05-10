package com.stokr.backtest.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "backtest_equity_curve")
public class BacktestEquityCurvePoint extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private BacktestRun run;

    @Column(name = "point_time", nullable = false)
    private Instant pointTime;

    @Column(name = "cumulative_pnl", nullable = false, precision = 24, scale = 8)
    private BigDecimal cumulativePnl;

    @Column(name = "drawdown", nullable = false, precision = 24, scale = 8)
    private BigDecimal drawdown;
}
