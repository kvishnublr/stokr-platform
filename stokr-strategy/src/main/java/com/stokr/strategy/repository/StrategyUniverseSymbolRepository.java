package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategyUniverseSymbol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface StrategyUniverseSymbolRepository extends JpaRepository<StrategyUniverseSymbol, UUID> {

    List<StrategyUniverseSymbol> findAllByGroupIdAndEnabledTrue(UUID groupId);

    List<StrategyUniverseSymbol> findAllByGroupId(UUID groupId);

    /** Returns distinct enabled symbols for a group — used by universe resolver */
    @Query("SELECT s.symbol FROM StrategyUniverseSymbol s WHERE s.group.id = :groupId AND s.enabled = true")
    List<String> findEnabledSymbolsByGroupId(@Param("groupId") UUID groupId);

    /** Returns trading symbols (broker-specific) for derivatives groups */
    @Query("SELECT COALESCE(s.tradingSymbol, s.symbol) FROM StrategyUniverseSymbol s WHERE s.group.id = :groupId AND s.enabled = true")
    List<String> findEnabledTradingSymbolsByGroupId(@Param("groupId") UUID groupId);

    long countByGroupIdAndEnabledTrue(UUID groupId);

    @Modifying
    @Query("DELETE FROM StrategyUniverseSymbol s WHERE s.group.id = :groupId")
    void deleteAllByGroupId(@Param("groupId") UUID groupId);
}
