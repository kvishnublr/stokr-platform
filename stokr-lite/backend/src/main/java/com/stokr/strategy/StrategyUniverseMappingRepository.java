package com.stokr.strategy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StrategyUniverseMappingRepository extends JpaRepository<StrategyUniverseMapping, Long> {

    List<StrategyUniverseMapping> findByStrategyId(Long strategyId);

    List<StrategyUniverseMapping> findByRuntimeEnabledTrue();

    Optional<StrategyUniverseMapping> findByStrategyIdAndUniverseGroupId(Long strategyId, Long universeGroupId);

    boolean existsByStrategyIdAndUniverseGroupId(Long strategyId, Long universeGroupId);
}
