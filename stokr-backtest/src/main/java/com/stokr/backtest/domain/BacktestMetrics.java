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
@Table(name = "backtest_metrics")
public class BacktestMetrics extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, unique = true)
    private BacktestRun run;

    @Column(name = "metrics_json")
    private String metricsJson;

    @Column(name = "rr_analysis_json")
    private String rrAnalysisJson;

    @Column(name = "streak_analysis_json")
    private String streakAnalysisJson;

    @Column(name = "win_rate", precision = 24, scale = 8)
    private BigDecimal winRate;

    @Column(name = "total_trades")
    private Integer totalTrades;

    @Column(name = "profit_factor", precision = 24, scale = 8)
    private BigDecimal profitFactor;

    @Column(name = "sharpe_ratio", precision = 24, scale = 8)
    private BigDecimal sharpeRatio;

    @Column(name = "max_drawdown", precision = 24, scale = 8)
    private BigDecimal maxDrawdown;

    @Column(name = "avg_rr", precision = 24, scale = 8)
    private BigDecimal avgRr;

    @Column(name = "expectancy", precision = 24, scale = 8)
    private BigDecimal expectancy;

    @Column(name = "total_pnl", precision = 24, scale = 8)
    private BigDecimal totalPnl;

    @Column(name = "avg_holding_time_seconds")
    private Long avgHoldingTimeSeconds;

    @Column(name = "replay_hash", length = 128)
    private String replayHash;
}
