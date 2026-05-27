package com.stokr.intraday.repository;

import com.stokr.intraday.domain.IndexSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for INDEX HUNT signals (NIFTY/BANKNIFTY options)
 */
@Repository
public interface IndexSignalRepository extends JpaRepository<IndexSignal, Long> {

    /**
     * Find all active signals (pending execution)
     */
    List<IndexSignal> findByIsActiveTrueOrderByQualityScoreDesc();

    /**
     * Find signals for a specific index
     */
    List<IndexSignal> findByIndexNameAndIsActiveTrueOrderByTimeDetectedDesc(String indexName);

    /**
     * Find signals detected after a specific time
     */
    List<IndexSignal> findByTimeDetectedAfterOrderByQualityScoreDesc(Instant timeAfter);

    /**
     * Find signals by quality score range
     */
    List<IndexSignal> findByQualityScoreGreaterThanEqualOrderByQualityScoreDesc(
            java.math.BigDecimal minQuality);

    /**
     * Find recent signals (last N hours) for a given index
     */
    @Query("SELECT s FROM IndexSignal s WHERE s.indexName = :indexName " +
            "AND s.timeDetected > :timeAfter ORDER BY s.timeDetected DESC")
    List<IndexSignal> findRecentSignals(@Param("indexName") String indexName,
                                         @Param("timeAfter") Instant timeAfter);

    /**
     * Find signals by execution status
     */
    List<IndexSignal> findByExecutionStatusOrderByTimeDetectedDesc(String status);

    /**
     * Count active signals for deduplication check
     * Returns count of signals for same index+direction within N minutes
     */
    @Query("SELECT COUNT(s) FROM IndexSignal s WHERE s.indexName = :indexName " +
            "AND s.direction = :direction AND s.isActive = true " +
            "AND s.timeDetected > :withinMinutes")
    long countRecentSignalsSameDirection(@Param("indexName") String indexName,
                                         @Param("direction") String direction,
                                         @Param("withinMinutes") Instant withinMinutes);

    /**
     * Get signal statistics for a day (P&L, win rate, etc.)
     */
    @Query("SELECT s FROM IndexSignal s WHERE s.closedAt IS NOT NULL " +
            "AND DATE(s.closedAt) = CURRENT_DATE " +
            "ORDER BY s.closedAt DESC")
    List<IndexSignal> findTodaysClosedSignals();

    /**
     * Find signals by outcome type (WIN/LOSS/EXPIRED)
     */
    List<IndexSignal> findByOutcomeTypeOrderByClosedAtDesc(String outcomeType);

    /**
     * Get win rate metrics
     */
    @Query("SELECT CAST(SUM(CASE WHEN s.outcomeType = 'WIN' THEN 1 ELSE 0 END) " +
            "AS FLOAT) / NULLIF(COUNT(s), 0) * 100 " +
            "FROM IndexSignal s WHERE s.indexName = :indexName " +
            "AND s.outcomeType IN ('WIN', 'LOSS')")
    Optional<Double> calculateWinRate(@Param("indexName") String indexName);
}
