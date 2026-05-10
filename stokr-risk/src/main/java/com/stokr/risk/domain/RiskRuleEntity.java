package com.stokr.risk.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "risk_rules")
public class RiskRuleEntity extends BaseEntity {

    @Column(name = "user_id")
    private java.util.UUID userId;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "config_json", columnDefinition = "text")
    private String configJson;

    @Column(name = "max_daily_loss", precision = 24, scale = 8)
    private BigDecimal maxDailyLoss;

    @Column(name = "max_open_positions")
    private Integer maxOpenPositions;

    @Column(name = "max_capital_allocation", precision = 24, scale = 8)
    private BigDecimal maxCapitalAllocation;

    @Column(name = "cooldown_seconds")
    private Integer cooldownSeconds;
}
