package com.stokr.backtest.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "backtest_results")
public class BacktestResult extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, unique = true)
    private BacktestRun run;

    @Column(name = "roi", precision = 24, scale = 8)
    private BigDecimal roi;

    @Column(name = "win_rate", precision = 24, scale = 8)
    private BigDecimal winRate;

    @Column(name = "sharpe_ratio", precision = 24, scale = 8)
    private BigDecimal sharpeRatio;

    @Column(name = "max_drawdown", precision = 24, scale = 8)
    private BigDecimal maxDrawdown;

    @Column(name = "pnl", precision = 24, scale = 8)
    private BigDecimal pnl;

    @Column(name = "profit_factor", precision = 24, scale = 8)
    private BigDecimal profitFactor;
}
