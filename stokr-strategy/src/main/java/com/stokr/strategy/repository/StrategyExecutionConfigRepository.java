package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategyExecutionConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StrategyExecutionConfigRepository extends JpaRepository<StrategyExecutionConfig, UUID> {

    /** Global admin config (user_id IS NULL). */
    Optional<StrategyExecutionConfig> findByUserIdIsNullAndStrategyKeyAndDeletedFalse(String strategyKey);

    /** Trader-specific override (user_id NOT NULL). */
    Optional<StrategyExecutionConfig> findByUserIdAndStrategyKeyAndDeletedFalse(UUID userId, String strategyKey);

    Optional<StrategyExecutionConfig> findByStrategyIdAndDeletedFalse(UUID strategyId);

    /** All global (admin) configs ordered by key. */
    List<StrategyExecutionConfig> findAllByUserIdIsNullAndDeletedFalseOrderByStrategyKeyAsc();

    /** All trader-specific overrides for a given user. */
    List<StrategyExecutionConfig> findByUserIdAndDeletedFalseOrderByStrategyKeyAsc(UUID userId);
}
