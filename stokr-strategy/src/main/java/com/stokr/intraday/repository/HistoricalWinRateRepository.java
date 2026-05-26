package com.stokr.intraday.repository;

import com.stokr.intraday.domain.HistoricalWinRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for historical win rates
 * Provides base probabilities for setup detection
 */
@Repository
public interface HistoricalWinRateRepository extends JpaRepository<HistoricalWinRate, Long> {

    /**
     * Get win rate for a setup in a specific market regime and time
     */
    @Query("SELECT hwr FROM HistoricalWinRate hwr WHERE hwr.setupType = :setupType " +
            "AND hwr.marketRegime = :regime AND hwr.hourOfDay = :hour " +
            "ORDER BY hwr.sampleSize DESC")
    Optional<HistoricalWinRate> findBySetupTypeAndRegimeAndHour(
            @Param("setupType") String setupType,
            @Param("regime") String regime,
            @Param("hour") Integer hour);

    /**
     * Get all win rates for a setup type in a specific regime
     */
    @Query("SELECT hwr FROM HistoricalWinRate hwr WHERE hwr.setupType = :setupType " +
            "AND hwr.marketRegime = :regime ORDER BY hwr.hourOfDay ASC")
    List<HistoricalWinRate> findBySetupTypeAndRegime(
            @Param("setupType") String setupType,
            @Param("regime") String regime);

    /**
     * Get high-confidence win rates (sample size >= 50)
     */
    @Query("SELECT hwr FROM HistoricalWinRate hwr WHERE hwr.setupType = :setupType " +
            "AND hwr.sampleSize >= 50 ORDER BY hwr.winRate DESC")
    List<HistoricalWinRate> findHighConfidenceRates(@Param("setupType") String setupType);

    /**
     * Get win rates for a setup in a specific sector
     */
    @Query("SELECT hwr FROM HistoricalWinRate hwr WHERE hwr.setupType = :setupType " +
            "AND hwr.sector = :sector AND hwr.sampleSize >= 20 " +
            "ORDER BY hwr.winRate DESC")
    List<HistoricalWinRate> findBySetupTypeAndSector(
            @Param("setupType") String setupType,
            @Param("sector") String sector);

    /**
     * Get best time of day for a specific setup
     */
    @Query("SELECT hwr FROM HistoricalWinRate hwr WHERE hwr.setupType = :setupType " +
            "AND hwr.hourOfDay IS NOT NULL ORDER BY hwr.winRate DESC")
    List<HistoricalWinRate> findBestHoursForSetup(@Param("setupType") String setupType);

    /**
     * Get all win rates that need updating (older than N days)
     */
    @Query("SELECT hwr FROM HistoricalWinRate hwr WHERE hwr.lastUpdated IS NULL " +
            "OR hwr.lastUpdated < :cutoff")
    List<HistoricalWinRate> findStaleWinRates(@Param("cutoff") Instant cutoff);

    /**
     * Get average win rate across all market regimes for a setup
     */
    @Query("SELECT AVG(hwr.winRate) FROM HistoricalWinRate hwr WHERE hwr.setupType = :setupType " +
            "AND hwr.sampleSize >= 20")
    Optional<BigDecimal> findAverageWinRate(@Param("setupType") String setupType);

    /**
     * Find gap fill specific win rates
     */
    @Query("SELECT hwr FROM HistoricalWinRate hwr WHERE hwr.setupType = 'gap_fill' " +
            "AND hwr.gapDirection = :direction AND hwr.gapSizeMin <= :gapSize " +
            "AND hwr.gapSizeMax >= :gapSize AND hwr.marketRegime = :regime")
    Optional<HistoricalWinRate> findGapFillRate(
            @Param("direction") String direction,
            @Param("gapSize") BigDecimal gapSize,
            @Param("regime") String regime);
}
