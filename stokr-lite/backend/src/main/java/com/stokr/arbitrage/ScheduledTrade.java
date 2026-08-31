package com.stokr.arbitrage;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import org.hibernate.annotations.Type;

@Entity
@Table(name = "scheduled_trades")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledTrade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id")
    private Long userId;

    private String underlying;
    private String action;
    private String strategyType;
    private int strike;
    private String broker;
    private int lots;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> legList;

    private LocalDateTime scheduledTime;
    private String status; // PENDING, EXECUTED, CANCELLED, FAILED
    
    private LocalDateTime createdAt;
    private LocalDateTime executedAt;
    
    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = "PENDING";
    }
}
