package com.stokr.risk.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "risk_events")
public class RiskEvent extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "rule_code", length = 64)
    private String ruleCode;

    @Column(name = "result", nullable = false, length = 16)
    private String result;

    @Column(name = "message", length = 500)
    private String message;
}
