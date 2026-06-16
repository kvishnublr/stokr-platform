package com.stokr.intraday.metrics.repository;

import com.stokr.intraday.metrics.domain.ConfidenceStrategyConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConfidenceStrategyConfigRepository
    extends JpaRepository<ConfidenceStrategyConfig, Long> {

    Optional<ConfidenceStrategyConfig> findByStrategyName(String strategyName);

    List<ConfidenceStrategyConfig> findByTraderId(UUID traderId);

    @Query("SELECT c FROM ConfidenceStrategyConfig c WHERE c.enabled = true")
    List<ConfidenceStrategyConfig> findByEnabledTrue();

    @Query("SELECT c FROM ConfidenceStrategyConfig c WHERE c.traderId = :traderId " +
           "AND c.enabled = true")
    List<ConfidenceStrategyConfig> findEnabledByTraderId(@Param("traderId") UUID traderId);

    @Query("SELECT COUNT(c) FROM ConfidenceStrategyConfig c " +
           "WHERE c.minConfidenceThreshold = :threshold AND c.enabled = true")
    long countByThreshold(@Param("threshold") Integer threshold);

    boolean existsByStrategyName(String strategyName);

    Optional<ConfidenceStrategyConfig> findByTraderIdAndMinConfidenceThreshold(
        UUID traderId,
        Integer threshold
    );
}
