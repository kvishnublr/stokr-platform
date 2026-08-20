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

    /** "PAPER" or the live broker name (e.g. "NAVIA", "ZERODHA") this position was actually
     *  entered through -- lets the Live Positions view tell real orders apart from paper
     *  ones even after the execution broker dropdown is switched to something else. */
    private String broker;

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

    /**
     * Structured legs for non-CE+PE+FUT positions (Box/Vertical/Butterfly/Condor/Iron Condor
     * spreads). Each entry: {symbol, strike, optionType, side, qty, entryPrice, exitPrice,
     * orderId}. Null means the legacy ceSymbol/peSymbol/futSymbol fields above are used.
     */
    @Column(columnDefinition = "TEXT")
    private String legsJson;

    private LocalDateTime enteredAt;
    private LocalDateTime exitedAt;
    private LocalDateTime createdAt;

    @Transient
    public java.util.List<java.util.Map<String, Object>> getLegs() {
        if (legsJson == null || legsJson.isEmpty()) return null;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var typeRef = new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {};
            return mapper.readValue(legsJson, typeRef);
        } catch (Exception e) {
            return null;
        }
    }

    public void setLegs(java.util.List<java.util.Map<String, Object>> legs) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            this.legsJson = (legs == null || legs.isEmpty()) ? null : mapper.writeValueAsString(legs);
        } catch (Exception e) {
            this.legsJson = null;
        }
    }

    @Transient
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", id);
        map.put("broker", broker);
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
        map.put("ceExitPrice", ceExitPrice != null ? ceExitPrice.doubleValue() : null);
        map.put("peExitPrice", peExitPrice != null ? peExitPrice.doubleValue() : null);
        map.put("futExitPrice", futExitPrice != null ? futExitPrice.doubleValue() : null);
        map.put("currentPnl", currentPnl != null ? currentPnl.doubleValue() : 0);
        map.put("targetEdge", targetEdge != null ? targetEdge.doubleValue() : null);
        map.put("status", status);
        map.put("ceOrderId", ceOrderId);
        map.put("peOrderId", peOrderId);
        map.put("futOrderId", futOrderId);
        map.put("errorMessage", errorMessage);
        var legs2 = getLegs();
        if (legs2 != null) map.put("legList", legs2);
        map.put("enteredAt", enteredAt != null ? enteredAt.toString() : null);
        map.put("exitedAt", exitedAt != null ? exitedAt.toString() : null);
        map.put("createdAt", createdAt != null ? createdAt.toString() : null);
        return map;
    }
}
