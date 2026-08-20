package com.stokr.arbitrage;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Tracks a Butterfly position under auto-roll monitoring: if spot sits outside the profit
 * zone continuously for the configured breach window, the position is closed automatically
 * and a re-centered replacement is proposed here, awaiting a one-click confirm before it's
 * actually entered. rollCount caps how many times a single lineage can re-bet before it's
 * left to ride to its normal stop-loss/target/expiry.
 */
@Entity
@Table(name = "auto_roll_state")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutoRollState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** LivePosition id of the very first position in this roll lineage -- never changes. */
    private Long originalPositionId;

    /** LivePosition id of the currently active/open position in this lineage, if any. */
    private Long currentPositionId;

    private String underlying;
    private String optionType;
    private Integer lots;

    @Builder.Default
    private Integer rollCount = 0;

    /** Set when spot first moves outside the profit zone; cleared when it re-enters. Used to
     *  measure continuous breach duration, not cumulative time-outside-zone. */
    private LocalDateTime breachStartedAt;

    /** ACTIVE (monitoring), PENDING_CONFIRM (closed, replacement awaiting confirm),
     *  MAX_ROLLS_REACHED, CLOSED_MANUALLY, DISMISSED, EXPIRED. */
    private String status;

    /** JSON: the proposed re-centered butterfly (strikes, legList, cost, etc.) awaiting confirm. */
    @Column(columnDefinition = "TEXT")
    private String pendingProposalJson;

    /** P&L the closed position realized right before this roll, for display in the confirm UI. */
    private java.math.BigDecimal lastClosedPnl;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Transient
    public java.util.Map<String, Object> getPendingProposal() {
        if (pendingProposalJson == null || pendingProposalJson.isEmpty()) return null;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(pendingProposalJson, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    public void setPendingProposal(java.util.Map<String, Object> proposal) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            this.pendingProposalJson = proposal == null ? null : mapper.writeValueAsString(proposal);
        } catch (Exception e) {
            this.pendingProposalJson = null;
        }
    }

    @Transient
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("originalPositionId", originalPositionId);
        m.put("currentPositionId", currentPositionId);
        m.put("underlying", underlying);
        m.put("optionType", optionType);
        m.put("rollCount", rollCount);
        m.put("status", status);
        m.put("proposal", getPendingProposal());
        m.put("lastClosedPnl", lastClosedPnl != null ? lastClosedPnl.doubleValue() : null);
        m.put("createdAt", createdAt != null ? createdAt.toString() : null);
        m.put("updatedAt", updatedAt != null ? updatedAt.toString() : null);
        return m;
    }
}
