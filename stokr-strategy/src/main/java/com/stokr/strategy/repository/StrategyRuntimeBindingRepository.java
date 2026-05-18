package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategyRuntimeBinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrategyRuntimeBindingRepository extends JpaRepository<StrategyRuntimeBinding, UUID> {

    List<StrategyRuntimeBinding> findAllByRuntimeEnabledTrue();

    @Query("SELECT b FROM StrategyRuntimeBinding b " +
           "JOIN FETCH b.strategyCatalog sc " +
           "JOIN FETCH b.universeGroup ug " +
           "WHERE b.runtimeEnabled = true AND sc.enabled = true AND sc.deleted = false AND ug.enabled = true")
    List<StrategyRuntimeBinding> findAllActiveBindings();

    List<StrategyRuntimeBinding> findAllByStrategyCatalogId(UUID strategyCatalogId);

    List<StrategyRuntimeBinding> findAllByUniverseGroupId(UUID universeGroupId);

    Optional<StrategyRuntimeBinding> findByStrategyCatalogIdAndUniverseGroupId(UUID strategyId, UUID groupId);

    Page<StrategyRuntimeBinding> findAllByStrategyCatalogId(UUID strategyCatalogId, Pageable pageable);

    boolean existsByStrategyCatalogIdAndUniverseGroupId(UUID strategyId, UUID groupId);

    /** Finds active bindings for a specific strategy key */
    @Query("SELECT b FROM StrategyRuntimeBinding b " +
           "JOIN FETCH b.strategyCatalog sc " +
           "JOIN FETCH b.universeGroup ug " +
           "WHERE sc.strategyKey = :strategyKey AND b.runtimeEnabled = true AND sc.deleted = false AND ug.enabled = true")
    List<StrategyRuntimeBinding> findActiveBindingsByStrategyKey(@Param("strategyKey") String strategyKey);
}
