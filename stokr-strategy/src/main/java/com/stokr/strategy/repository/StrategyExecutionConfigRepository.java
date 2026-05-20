package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategyExecutionConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StrategyExecutionConfigRepository extends JpaRepository<StrategyExecutionConfig, UUID> {

    Optional<StrategyExecutionConfig> findByStrategyKeyAndDeletedFalse(String strategyKey);

    Optional<StrategyExecutionConfig> findByStrategyIdAndDeletedFalse(UUID strategyId);

    List<StrategyExecutionConfig> findAllByDeletedFalseOrderByStrategyKeyAsc();
}
