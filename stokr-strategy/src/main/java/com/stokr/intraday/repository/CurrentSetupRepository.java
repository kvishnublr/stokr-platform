package com.stokr.intraday.repository;

import com.stokr.intraday.domain.CurrentSetup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for current setup detection results
 * Provides access to real-time ranking board of top setups
 */
@Repository
public interface CurrentSetupRepository extends JpaRepository<CurrentSetup, Long> {

    /**
     * Get top N active setups by quality score
     * Used for ranking board display (top 12)
     */
    @Query("SELECT cs FROM CurrentSetup cs WHERE cs.isActive = true AND cs.expiresAt > CURRENT_TIMESTAMP " +
            "ORDER BY cs.qualityScore DESC LIMIT :limit")
    List<CurrentSetup> findTopActiveSetups(@Param("limit") int limit);

    /**
     * Get all active setups for a specific stock
     */
    @Query("SELECT cs FROM CurrentSetup cs WHERE cs.stockId = :stockId AND cs.isActive = true " +
            "AND cs.expiresAt > CURRENT_TIMESTAMP ORDER BY cs.timeDetected DESC")
    List<CurrentSetup> findActiveSetupsByStock(@Param("stockId") String stockId);

    /**
     * Get setups of a specific type that are still active
     */
    @Query("SELECT cs FROM CurrentSetup cs WHERE cs.setupType = :setupType AND cs.isActive = true " +
            "AND cs.expiresAt > CURRENT_TIMESTAMP ORDER BY cs.qualityScore DESC")
    List<CurrentSetup> findActiveSetupsByType(@Param("setupType") String setupType);

    /**
     * Get setups matching minimum probability and risk/reward thresholds
     * Used for filtering recommendations
     */
    @Query("SELECT cs FROM CurrentSetup cs WHERE cs.isActive = true AND cs.expiresAt > CURRENT_TIMESTAMP " +
            "AND cs.adjustedProbability >= :minProbability " +
            "AND cs.riskRewardRatio >= :minRiskReward " +
            "ORDER BY cs.qualityScore DESC")
    List<CurrentSetup> findRecommendedSetups(
            @Param("minProbability") java.math.BigDecimal minProbability,
            @Param("minRiskReward") java.math.BigDecimal minRiskReward);

    /**
     * Get setups detected between two timestamps
     * Used for analysis and backtesting
     */
    @Query("SELECT cs FROM CurrentSetup cs WHERE cs.timeDetected >= :fromTime AND cs.timeDetected <= :toTime " +
            "ORDER BY cs.timeDetected DESC")
    List<CurrentSetup> findSetupsBetween(
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime);

    /**
     * Find expired setups for cleanup
     */
    @Query("SELECT cs FROM CurrentSetup cs WHERE cs.expiresAt IS NOT NULL AND cs.expiresAt < CURRENT_TIMESTAMP")
    List<CurrentSetup> findExpiredSetups();

    /**
     * Count active setups by type
     */
    @Query("SELECT COUNT(cs) FROM CurrentSetup cs WHERE cs.setupType = :setupType AND cs.isActive = true " +
            "AND cs.expiresAt > CURRENT_TIMESTAMP")
    long countActiveByType(@Param("setupType") String setupType);

    /**
     * Get recently detected setups (last N hours)
     * Used for fresh ranking board
     */
    @Query("SELECT cs FROM CurrentSetup cs WHERE cs.isActive = true " +
            "AND cs.timeDetected >= :since AND cs.expiresAt > CURRENT_TIMESTAMP " +
            "ORDER BY cs.qualityScore DESC")
    List<CurrentSetup> findRecentSetups(@Param("since") Instant since);
}
