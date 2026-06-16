package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategyRuntimeBinding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    @Modifying
    @Query("""
            DELETE FROM StrategyRuntimeBinding b
            WHERE b.strategyCatalog.id = :catalogId
              AND b.universeGroup.groupKey <> :keepGroupKey
            """)
    int deleteByStrategyCatalogIdAndUniverseGroupGroupKeyNot(
            @Param("catalogId") UUID catalogId,
            @Param("keepGroupKey") String keepGroupKey);

    List<StrategyRuntimeBinding> findAllByUniverseGroupId(UUID universeGroupId);

    Optional<StrategyRuntimeBinding> findByStrategyCatalogIdAndUniverseGroupId(UUID strategyId, UUID groupId);

    Page<StrategyRuntimeBinding> findAllByStrategyCatalogId(UUID strategyCatalogId, Pageable pageable);

    boolean existsByStrategyCatalogIdAndUniverseGroupId(UUID strategyId, UUID groupId);

    /** Finds active bindings for multiple strategy keys in one query (for catalog page) */
    @Query("SELECT b FROM StrategyRuntimeBinding b " +
           "JOIN FETCH b.strategyCatalog sc " +
           "JOIN FETCH b.universeGroup ug " +
           "WHERE sc.strategyKey IN :keys AND b.runtimeEnabled = true AND sc.deleted = false AND ug.enabled = true")
    List<StrategyRuntimeBinding> findAllActiveBindingsByStrategyKeys(@Param("keys") List<String> keys);

    /** Finds active bindings for a specific strategy key */
    @Query("SELECT b FROM StrategyRuntimeBinding b " +
           "JOIN FETCH b.strategyCatalog sc " +
           "JOIN FETCH b.universeGroup ug " +
           "WHERE sc.strategyKey = :strategyKey AND b.runtimeEnabled = true AND sc.deleted = false AND ug.enabled = true")
    List<StrategyRuntimeBinding> findActiveBindingsByStrategyKey(@Param("strategyKey") String strategyKey);

    /**
     * Returns all allowed trading symbols (broker-key) for a strategy key, resolved via
     * its active runtime bindings and universe group membership.
     * Empty result means no binding exists ??? caller should treat as "allow all" (legacy).
     */
    @Query("""
           SELECT DISTINCT COALESCE(s.tradingSymbol, s.symbol)
           FROM StrategyRuntimeBinding b
           JOIN b.strategyCatalog sc
           JOIN b.universeGroup ug
           JOIN com.stokr.strategy.domain.StrategyUniverseSymbol s ON s.group.id = ug.id
           WHERE sc.strategyKey = :strategyKey
             AND b.runtimeEnabled = true
             AND sc.deleted = false
             AND ug.enabled = true
             AND s.enabled = true
           """)
    List<String> findAllowedSymbolsForStrategyKey(@Param("strategyKey") String strategyKey);

    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT sc.strategy_key, ug.group_key, COUNT(*) AS cnt
                FROM strategy_runtime_bindings b
                JOIN strategy_definitions sc ON sc.id = b.strategy_catalog_id
                JOIN strategy_universe_groups ug ON ug.id = b.universe_group_id
                WHERE b.runtime_enabled = TRUE AND sc.deleted = FALSE
                GROUP BY sc.strategy_key, ug.group_key
                HAVING COUNT(*) > 1
            ) dup
            """, nativeQuery = true)
    long countDuplicateActiveBindings();
}
