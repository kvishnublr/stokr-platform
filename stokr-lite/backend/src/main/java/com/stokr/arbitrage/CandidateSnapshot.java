package com.stokr.arbitrage;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Periodic snapshot of the "Candidates" (not arbitrage) discovery scan -- Vertical/Butterfly/
 * Condor spreads priced cheap relative to width, evaluated but never executed automatically.
 * These were never persisted anywhere before, so there was no way to answer "how many
 * candidates showed up today/this week". One row per underlying per strategy per snapshot,
 * with just a count + the top (highest-POP) candidate's numbers -- not every candidate, to
 * keep this lightweight (a full scan can return 100+ candidates per strategy).
 */
@Entity
@Table(name = "candidate_snapshots")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandidateSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String strategyType;
    private String underlying;

    private Integer candidateCount;
    private Double avgPop;

    private String topOptionType;
    private String topStrikes;
    private Double topPop;
    private Double topCostPerLot;
    private Double topMaxLoss;
    private Double topMaxProfit;
    private Double topMarginEstimate;

    private LocalDateTime snapshotTime;
}
