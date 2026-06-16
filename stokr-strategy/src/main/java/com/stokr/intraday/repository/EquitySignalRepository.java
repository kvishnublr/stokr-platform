package com.stokr.intraday.repository;

import com.stokr.intraday.domain.EquitySignal;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface EquitySignalRepository extends JpaRepository<EquitySignal, Long> {

    // Get active pending signals
    @Query("SELECT e FROM EquitySignal e WHERE e.executionStatus = 'PENDING' AND e.isActive = true ORDER BY e.qualityScore DESC, e.timeDetected DESC")
    List<EquitySignal> findActivePendingSignals();

    // Get signals by symbol
    @Query("SELECT e FROM EquitySignal e WHERE e.symbolName = :symbol AND e.executionStatus = 'PENDING' ORDER BY e.qualityScore DESC")
    List<EquitySignal> findPendingBySymbol(@Param("symbol") String symbol);

    // Get high confidence signals (3-TF alignment)
    @Query("SELECT e FROM EquitySignal e WHERE e.confidenceLevel = 3 AND e.executionStatus = 'PENDING' ORDER BY e.qualityScore DESC")
    List<EquitySignal> findHighConfidenceSignals();

    // Get high quality signals
    @Query("SELECT e FROM EquitySignal e WHERE e.qualityScore >= :minQuality AND e.executionStatus = 'PENDING' ORDER BY e.qualityScore DESC")
    List<EquitySignal> findHighQualitySignals(@Param("minQuality") java.math.BigDecimal minQuality);

    // Get signals by sector
    @Query("SELECT e FROM EquitySignal e WHERE e.sector = :sector AND e.executionStatus = 'PENDING' ORDER BY e.qualityScore DESC")
    List<EquitySignal> findBySector(@Param("sector") String sector);

    // Count active signals in sector
    @Query("SELECT COUNT(e) FROM EquitySignal e WHERE e.sector = :sector AND e.executionStatus = 'FILLED' AND e.isActive = true")
    int countActiveBySector(@Param("sector") String sector);

    // Get recent signals (last N hours)
    @Query("SELECT e FROM EquitySignal e WHERE e.timeDetected >= :since ORDER BY e.timeDetected DESC")
    List<EquitySignal> findRecentSignals(@Param("since") Instant since);

    @Query("SELECT e FROM EquitySignal e WHERE e.createdAt >= :start AND e.createdAt < :end ORDER BY e.createdAt DESC")
    List<EquitySignal> findSignalsCreatedBetween(@Param("start") Instant start, @Param("end") Instant end);

    @Query("SELECT e FROM EquitySignal e WHERE e.outcomeType IN ('WIN', 'LOSS') ORDER BY e.timeClosedAt DESC")
    List<EquitySignal> findRecentOutcomes(Pageable pageable);

    // Get signals by tier
    @Query("SELECT e FROM EquitySignal e WHERE e.tier = :tier AND e.executionStatus = 'PENDING' ORDER BY e.qualityScore DESC")
    List<EquitySignal> findByTier(@Param("tier") String tier);
}
