package com.stokr.arbitrage;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;

@Entity
@Table(name = "option_arb_opportunities")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OptionArbOpportunity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime scanTime;

    private String underlying;

    @Column(name = "opportunity_type")
    private String type;

    private Integer strike;
    private String action;
    private String legs;
    private String description;

    @Column(name = "spot_price")
    private BigDecimal spotPrice;

    @Column(name = "futures_price")
    private BigDecimal futuresPrice;

    @Column(name = "ce_entry_price")
    private BigDecimal ceEntryPrice;

    @Column(name = "pe_entry_price")
    private BigDecimal peEntryPrice;

    private BigDecimal ceBid;
    private BigDecimal ceAsk;
    private BigDecimal peBid;
    private BigDecimal peAsk;

    @Column(name = "edge_points")
    private BigDecimal edgePoints;

    @Column(name = "edge_after_costs")
    private BigDecimal edgeAfterCosts;

    private BigDecimal confidence;

    @Column(name = "days_to_expiry")
    private BigDecimal daysToExpiry;

    private LocalDate expiryDate;

    @Builder.Default
    private String status = "OPEN";

    @Column(name = "ce_exit_price")
    private BigDecimal ceExitPrice;

    @Column(name = "pe_exit_price")
    private BigDecimal peExitPrice;

    @Column(name = "exit_spot_price")
    private BigDecimal exitSpotPrice;

    private LocalDateTime exitTime;

    @Column(name = "pnl_points")
    private BigDecimal pnlPoints;

    @Column(name = "pnl_amount")
    private BigDecimal pnlAmount;

    @Column(name = "pnl_after_costs")
    private BigDecimal pnlAfterCosts;

    @Column(columnDefinition = "jsonb")
    private String costBreakdownJson;

    private String notes;

    private LocalDateTime createdAt;

    @Transient
    public java.util.Map<String, Double> getCostBreakdown() {
        if (costBreakdownJson == null || costBreakdownJson.isEmpty()) return null;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var typeRef = new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Double>>() {};
            return mapper.readValue(costBreakdownJson, typeRef);
        } catch (Exception e) {
            return null;
        }
    }

    public void setCostBreakdown(java.util.Map<String, Double> breakdown) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            this.costBreakdownJson = mapper.writeValueAsString(breakdown);
        } catch (Exception e) {
            this.costBreakdownJson = null;
        }
    }
}
