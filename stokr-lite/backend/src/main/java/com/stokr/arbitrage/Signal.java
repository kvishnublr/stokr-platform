package com.stokr.arbitrage;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "signals")
public class Signal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime scanTime;

    @Column(length = 20)
    private String underlying;

    private Double strike;

    @Column(length = 20)
    private String action;

    @Column(name = "strategy_type", length = 30)
    private String strategyType;

    @Column(name = "spot_price")
    private BigDecimal spotPrice;

    @Column(name = "futures_price")
    private BigDecimal futuresPrice;

    @Column(name = "ce_price")
    private BigDecimal cePrice;

    @Column(name = "pe_price")
    private BigDecimal pePrice;

    @Column(name = "ce_bid")
    private BigDecimal ceBid;

    @Column(name = "ce_ask")
    private BigDecimal ceAsk;

    @Column(name = "pe_bid")
    private BigDecimal peBid;

    @Column(name = "pe_ask")
    private BigDecimal peAsk;

    @Column(name = "edge_points")
    private BigDecimal edgePoints;

    @Column(name = "edge_after_costs")
    private BigDecimal edgeAfterCosts;

    @Column(name = "days_to_expiry")
    private BigDecimal daysToExpiry;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "ce_symbol", length = 50)
    private String ceSymbol;

    @Column(name = "pe_symbol", length = 50)
    private String peSymbol;

    @Column(name = "fut_symbol", length = 50)
    private String futSymbol;

    @Column(length = 20)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private LocalDateTime createdAt;

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", id);
        map.put("scanTime", scanTime);
        map.put("underlying", underlying);
        map.put("strike", strike);
        map.put("action", action);
        map.put("strategyType", strategyType);
        map.put("spotPrice", spotPrice);
        map.put("futuresPrice", futuresPrice);
        map.put("cePrice", cePrice);
        map.put("pePrice", pePrice);
        map.put("edgePoints", edgePoints);
        map.put("edgeAfterCosts", edgeAfterCosts);
        map.put("daysToExpiry", daysToExpiry);
        map.put("expiryDate", expiryDate);
        map.put("ceSymbol", ceSymbol);
        map.put("peSymbol", peSymbol);
        map.put("futSymbol", futSymbol);
        map.put("status", status);
        map.put("notes", notes);
        return map;
    }
}
