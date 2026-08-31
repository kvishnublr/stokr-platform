package com.stokr.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trader_risk_limits")
@Data
@NoArgsConstructor
public class TraderRiskLimit {
    @Id
    private Long userId;

    private BigDecimal maxDailyLoss = new BigDecimal("5000.00");
    
    private Boolean tradingEnabled = true;
    
    private LocalDateTime updatedAt = LocalDateTime.now();
}
