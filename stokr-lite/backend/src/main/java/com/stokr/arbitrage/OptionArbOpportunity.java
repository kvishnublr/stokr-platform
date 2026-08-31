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
    @Column(name = "user_id")
    private Long userId;

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

    @Column(name = "strategy_type")
    @Builder.Default
    private String strategyType = "NORMAL_PARITY";

    @Column(columnDefinition = "jsonb")
    private String costBreakdownJson;

    /**
     * Structured trade legs for non-CE+PE+FUT strategies (Box/Vertical/Butterfly/Condor/
     * Iron Condor spreads). Null means "legacy CE+PE+FUT conversion/reversal shape".
     */
    private String legsJson;

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

    @Transient
    public java.util.List<java.util.Map<String, Object>> getLegList() {
        if (legsJson == null || legsJson.isEmpty()) return null;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var typeRef = new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {};
            return mapper.readValue(legsJson, typeRef);
        } catch (Exception e) {
            return null;
        }
    }

    public void setLegList(java.util.List<java.util.Map<String, Object>> legs) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            this.legsJson = (legs == null || legs.isEmpty()) ? null : mapper.writeValueAsString(legs);
        } catch (Exception e) {
            this.legsJson = null;
        }
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", id);
        map.put("scanTime", scanTime != null ? scanTime.toString() : null);
        map.put("underlying", underlying);
        map.put("type", type);
        map.put("strike", strike);
        map.put("action", action);
        map.put("legs", legs);
        map.put("description", description);
        map.put("spotPrice", spotPrice != null ? spotPrice.doubleValue() : 0);
        map.put("futuresPrice", futuresPrice != null ? futuresPrice.doubleValue() : 0);
        
        double ceP = ceEntryPrice != null ? ceEntryPrice.doubleValue() : (ceBid != null ? ceBid.doubleValue() : 0);
        double peP = peEntryPrice != null ? peEntryPrice.doubleValue() : (peBid != null ? peBid.doubleValue() : 0);
        map.put("ceEntryPrice", ceP);
        map.put("peEntryPrice", peP);
        map.put("cePrice", ceP);
        map.put("pePrice", peP);

        map.put("ceBid", ceBid != null ? ceBid.doubleValue() : 0);
        map.put("ceAsk", ceAsk != null ? ceAsk.doubleValue() : 0);
        map.put("peBid", peBid != null ? peBid.doubleValue() : 0);
        map.put("peAsk", peAsk != null ? peAsk.doubleValue() : 0);
        map.put("edgePoints", edgePoints != null ? edgePoints.doubleValue() : 0);
        map.put("edgeAfterCosts", edgeAfterCosts != null ? edgeAfterCosts.doubleValue() : 0);
        map.put("confidence", confidence != null ? confidence.doubleValue() : 0);
        map.put("daysToExpiry", daysToExpiry != null ? daysToExpiry.doubleValue() : 0);
        map.put("expiryDate", expiryDate != null ? expiryDate.toString() : null);
        map.put("status", status);
        map.put("strategyType", strategyType);
        map.put("pnlAfterCosts", pnlAfterCosts != null ? pnlAfterCosts.doubleValue() : null);
        map.put("pnlPoints", pnlPoints != null ? pnlPoints.doubleValue() : null);
        map.put("pnlAmount", pnlAmount != null ? pnlAmount.doubleValue() : null);
        map.put("ceExitPrice", ceExitPrice != null ? ceExitPrice.doubleValue() : null);
        map.put("peExitPrice", peExitPrice != null ? peExitPrice.doubleValue() : null);
        map.put("entryTime", scanTime != null ? scanTime.toString() : null);
        map.put("exitTime", exitTime != null ? exitTime.toString() : null);
        int lotSize = OptionChainService.getLotSize(underlying);
        map.put("lotSize", lotSize);
        map.put("notes", notes);
        map.put("createdAt", createdAt != null ? createdAt.toString() : null);
        var costs = getCostBreakdown();
        if (costs != null) map.put("costBreakdown", costs);
        var legs2 = getLegList();
        if (legs2 != null) map.put("legList", legs2);
        return map;
    }
}
