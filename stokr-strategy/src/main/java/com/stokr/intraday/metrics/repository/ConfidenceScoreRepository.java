package com.stokr.intraday.metrics.repository;

import com.stokr.intraday.metrics.domain.ConfidenceScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConfidenceScoreRepository extends JpaRepository<ConfidenceScore, Long> {

    Optional<ConfidenceScore> findFirstBySymbolOrderByTimestampDesc(@Param("symbol") String symbol);

    @Query("SELECT c FROM ConfidenceScore c WHERE c.confidenceScore > :threshold " +
           "AND c.timestamp > :since ORDER BY c.confidenceScore DESC, c.timestamp DESC")
    List<ConfidenceScore> findByConfidenceScoreGreaterThanAndTimestampAfterOrderByConfidenceScoreDescTimestampDesc(
        @Param("threshold") Integer threshold,
        @Param("since") Instant since
    );

    @Query("SELECT DISTINCT c.symbol FROM ConfidenceScore c " +
           "WHERE c.timestamp > :since ORDER BY c.symbol")
    List<String> findDistinctSymbolsSince(@Param("since") Instant since);

    @Query("SELECT COUNT(DISTINCT c.symbol) FROM ConfidenceScore c " +
           "WHERE c.confidenceScore > :threshold AND c.timestamp > :since")
    long countSymbolsAboveThreshold(
        @Param("threshold") Integer threshold,
        @Param("since") Instant since
    );

    @Query("SELECT COUNT(*) FROM ConfidenceScore c " +
           "WHERE c.confidenceScore > :threshold AND c.timestamp > :since")
    long countScoresAboveThreshold(
        @Param("threshold") Integer threshold,
        @Param("since") Instant since
    );

    List<ConfidenceScore> findBySymbolAndTimestampAfterOrderByTimestampDesc(
        String symbol,
        Instant timestamp
    );

    @Query("SELECT c FROM ConfidenceScore c WHERE c.symbol = :symbol " +
           "AND c.timestamp BETWEEN :startTime AND :endTime " +
           "ORDER BY c.timestamp DESC")
    List<ConfidenceScore> findBySymbolAndTimeRange(
        @Param("symbol") String symbol,
        @Param("startTime") Instant startTime,
        @Param("endTime") Instant endTime
    );

    @Query("SELECT AVG(c.confidenceScore) FROM ConfidenceScore c " +
           "WHERE c.symbol = :symbol AND c.timestamp > :since")
    Double getAverageConfidenceForSymbol(
        @Param("symbol") String symbol,
        @Param("since") Instant since
    );

    List<ConfidenceScore> findByTimestampAfterOrderByConfidenceScoreDescTimestampDesc(
        @Param("since") Instant since,
        Pageable pageable
    );
}
