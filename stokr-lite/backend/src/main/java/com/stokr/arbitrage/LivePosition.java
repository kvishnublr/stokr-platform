package com.stokr.arbitrage;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "live_positions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LivePosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private Long opportunityId;

    private String underlying;
    private Integer strike;
    private String action;
    private String strategyType;

    private String ceSymbol;
    private String peSymbol;
    private String futSymbol;

    private Integer lots;
    private Integer lotSize;

    @Column(precision = 12, scale = 2)
    private BigDecimal ceEntryPrice;
    @Column(precision = 12, scale = 2)
    private BigDecimal peEntryPrice;
    @Column(precision = 12, scale = 2)
    private BigDecimal futEntryPrice;

    private BigDecimal ceExitPrice;
    private BigDecimal peExitPrice;
    private BigDecimal futExitPrice;

    @Column(precision = 12, scale = 2)
    private BigDecimal entryCost;

    @Column(precision = 12, scale = 2)
    private BigDecimal currentPnl;

    @Column(precision = 12, scale = 2)
    private BigDecimal targetEdge;

    private String status;

    private String ceOrderId;
    private String peOrderId;
    private String futOrderId;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private LocalDateTime enteredAt;
    private LocalDateTime exitedAt;
    private LocalDateTime createdAt;

    @Transient
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", id);
        map.put("opportunityId", opportunityId);
        map.put("underlying", underlying);
        map.put("strike", strike);
        map.put("action", action);
        map.put("strategyType", strategyType);
        map.put("ceSymbol", ceSymbol);
        map.put("peSymbol", peSymbol);
        map.put("lots", lots);
        map.put("lotSize", lotSize);
        map.put("ceEntryPrice", ceEntryPrice != null ? ceEntryPrice.doubleValue() : null);
        map.put("peEntryPrice", peEntryPrice != null ? peEntryPrice.doubleValue() : null);
        map.put("futEntryPrice", futEntryPrice != null ? futEntryPrice.doubleValue() : null);
        map.put("currentPnl", currentPnl != null ? currentPnl.doubleValue() : 0);
        map.put("targetEdge", targetEdge != null ? targetEdge.doubleValue() : null);
        map.put("status", status);
        map.put("ceOrderId", ceOrderId);
        map.put("peOrderId", peOrderId);
        map.put("futOrderId", futOrderId);
        map.put("errorMessage", errorMessage);
        map.put("enteredAt", enteredAt != null ? enteredAt.toString() : null);
        map.put("exitedAt", exitedAt != null ? exitedAt.toString() : null);
        map.put("createdAt", createdAt != null ? createdAt.toString() : null);
        return map;
    }
}
