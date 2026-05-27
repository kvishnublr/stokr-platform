package com.stokr.intraday.repository;

import com.stokr.intraday.domain.FuturesSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface FuturesSignalRepository extends JpaRepository<FuturesSignal, Long> {

    // Get active (pending) signals
    @Query("SELECT f FROM FuturesSignal f WHERE f.executionStatus = 'PENDING' AND f.isActive = true ORDER BY f.qualityScore DESC, f.timeDetected DESC")
    List<FuturesSignal> findActivePendingSignals();

    // Get signals by strategy
    @Query("SELECT f FROM FuturesSignal f WHERE f.strategyName = :strategy AND f.isActive = true ORDER BY f.timeDetected DESC")
    List<FuturesSignal> findByStrategy(@Param("strategy") String strategy);

    // Get signals by symbol
    @Query("SELECT f FROM FuturesSignal f WHERE f.symbolName = :symbol AND f.executionStatus = 'PENDING' ORDER BY f.qualityScore DESC")
    List<FuturesSignal> findPendingBySymbol(@Param("symbol") String symbol);

    // Get high quality signals
    @Query("SELECT f FROM FuturesSignal f WHERE f.qualityScore >= :minQuality AND f.executionStatus = 'PENDING' ORDER BY f.qualityScore DESC")
    List<FuturesSignal> findHighQualitySignals(@Param("minQuality") java.math.BigDecimal minQuality);

    // Get recent signals (last N hours)
    @Query("SELECT f FROM FuturesSignal f WHERE f.timeDetected >= :since ORDER BY f.timeDetected DESC")
    List<FuturesSignal> findRecentSignals(@Param("since") Instant since);

    // Get today's stats
    @Query("SELECT f FROM FuturesSignal f WHERE DATE(f.createdAt) = CURDATE() ORDER BY f.createdAt DESC")
    List<FuturesSignal> findTodaysSignals();

    // Get closed signals (wins/losses)
    @Query("SELECT f FROM FuturesSignal f WHERE f.outcomeType IN ('WIN', 'LOSS') AND f.strategyName = :strategy ORDER BY f.timeClosedAt DESC LIMIT :limit")
    List<FuturesSignal> findRecentOutcomes(@Param("strategy") String strategy, @Param("limit") int limit);
}
