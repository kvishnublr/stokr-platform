package com.stokr.intraday.metrics.repository;

import com.stokr.intraday.metrics.domain.IntelligenceScore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface IntelligenceScoreRepository extends JpaRepository<IntelligenceScore, Long> {

    Optional<IntelligenceScore> findBySymbol(String symbol);

    @Query("SELECT s FROM IntelligenceScore s WHERE s.confidence > :minConfidence ORDER BY s.confidence DESC")
    List<IntelligenceScore> findHighConfidenceScores(@Param("minConfidence") Integer minConfidence);

    @Query("SELECT s FROM IntelligenceScore s WHERE s.recommendation = :recommendation ORDER BY s.lastUpdated DESC")
    List<IntelligenceScore> findByRecommendation(@Param("recommendation") String recommendation);

    @Query("SELECT s FROM IntelligenceScore s WHERE s.shouldEnhanceConfidence = true ORDER BY s.confidence DESC")
    List<IntelligenceScore> findSignalsToEnhance();

    @Query("SELECT s FROM IntelligenceScore s WHERE s.shouldSkip = true ORDER BY s.confidence ASC")
    List<IntelligenceScore> findSignalsToSkip();

    @Query("SELECT s FROM IntelligenceScore s WHERE s.buyerPressureScore > :threshold ORDER BY s.buyerPressureScore DESC")
    List<IntelligenceScore> findStrongBuyerPressure(@Param("threshold") Integer threshold);

    @Query("SELECT s FROM IntelligenceScore s WHERE s.sellerPressureScore > :threshold ORDER BY s.sellerPressureScore DESC")
    List<IntelligenceScore> findStrongSellerPressure(@Param("threshold") Integer threshold);

    @Query("SELECT s FROM IntelligenceScore s WHERE s.lastUpdated > :since ORDER BY s.lastUpdated DESC")
    List<IntelligenceScore> findRecentlyUpdated(@Param("since") Instant since);

    Page<IntelligenceScore> findAllByOrderByLastUpdatedDesc(Pageable pageable);

    Page<IntelligenceScore> findAllByOrderByConfidenceDesc(Pageable pageable);

    @Query("SELECT COUNT(DISTINCT s.symbol) FROM IntelligenceScore s WHERE s.lastUpdated > :since")
    Long countRecentlyUpdated(@Param("since") Instant since);
}
