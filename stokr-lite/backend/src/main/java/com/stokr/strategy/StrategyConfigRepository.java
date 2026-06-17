package com.stokr.strategy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StrategyConfigRepository extends JpaRepository<StrategyConfig, Long> {

    Optional<StrategyConfig> findByStrategyId(Long strategyId);

    boolean existsByStrategyId(Long strategyId);
}
