package com.stokr.strategy.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Persisted KNN pattern memory entry for ADV_CASH strategy.
 * Matches Python adv_cash_improved.py KNN pattern store.
 *
 * Each row = one resolved trade with its 6 feature vector + outcome (win/loss).
 * On startup, the strategy loads all entries to rebuild in-memory KNN vectors.
 */
@Getter
@Setter
@Entity
@Table(name = "knn_pattern_entries")
public class KnnPatternEntry extends BaseEntity {

    @Column(name = "strategy_key", nullable = false, length = 50)
    private String strategyKey;

    @Column(name = "symbol", nullable = false, length = 50)
    private String symbol;

    @Column(name = "feat_obi", nullable = false)
    private double featObi;

    @Column(name = "feat_slope", nullable = false)
    private double featSlope;

    @Column(name = "feat_vol", nullable = false)
    private double featVol;

    @Column(name = "feat_vix", nullable = false)
    private double featVix;

    @Column(name = "feat_time", nullable = false)
    private double featTime;

    @Column(name = "feat_direction", nullable = false)
    private double featDirection;

    @Column(name = "outcome", nullable = false)
    private int outcome;

    @Column(name = "trade_time", nullable = false)
    private Instant tradeTime;

    @Column(name = "raw_obi")
    private Double rawObi;

    @Column(name = "raw_slope")
    private Double rawSlope;

    @Column(name = "raw_vol")
    private Double rawVol;

    @Column(name = "raw_vix")
    private Double rawVix;

    @Column(name = "raw_time_min")
    private Integer rawTimeMin;

    @Column(name = "raw_direction", length = 10)
    private String rawDirection;
}
