package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategyUniverseSymbol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface StrategyUniverseSymbolRepository extends JpaRepository<StrategyUniverseSymbol, UUID> {

    List<StrategyUniverseSymbol> findAllByGroupIdAndEnabledTrue(UUID groupId);

    List<StrategyUniverseSymbol> findAllByGroupId(UUID groupId);

    List<StrategyUniverseSymbol> findAllByEnabledTrueAndExchangeIgnoreCase(String exchange);

    @Query("""
           SELECT s
           FROM StrategyUniverseSymbol s
           JOIN FETCH s.group g
           WHERE s.enabled = true
             AND g.enabled = true
             AND UPPER(g.groupKey) IN :groupKeys
           ORDER BY g.groupKey, s.symbol
           """)
    List<StrategyUniverseSymbol> findAllEnabledByGroupKeys(@Param("groupKeys") List<String> groupKeys);

    /** Returns distinct enabled symbols for a group ??? used by universe resolver */
    @Query("SELECT s.symbol FROM StrategyUniverseSymbol s WHERE s.group.id = :groupId AND s.enabled = true")
    List<String> findEnabledSymbolsByGroupId(@Param("groupId") UUID groupId);

    /** Returns trading symbols (broker-specific) for derivatives groups */
    @Query("SELECT COALESCE(s.tradingSymbol, s.symbol) FROM StrategyUniverseSymbol s WHERE s.group.id = :groupId AND s.enabled = true")
    List<String> findEnabledTradingSymbolsByGroupId(@Param("groupId") UUID groupId);

    long countByGroupIdAndEnabledTrue(UUID groupId);

    @Modifying
    @Query("DELETE FROM StrategyUniverseSymbol s WHERE s.group.id = :groupId")
    void deleteAllByGroupId(@Param("groupId") UUID groupId);

    /** Search enabled symbols across all groups by partial name match */
    @Query("SELECT DISTINCT s.symbol FROM StrategyUniverseSymbol s WHERE s.enabled = true AND UPPER(s.symbol) LIKE UPPER(CONCAT('%', :q, '%')) ORDER BY s.symbol")
    List<String> searchSymbols(@Param("q") String q, Pageable pageable);
}
