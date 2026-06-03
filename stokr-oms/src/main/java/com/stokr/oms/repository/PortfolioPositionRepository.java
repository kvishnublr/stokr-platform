package com.stokr.oms.repository;

import com.stokr.oms.domain.PortfolioPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortfolioPositionRepository extends JpaRepository<PortfolioPosition, UUID> {

    List<PortfolioPosition> findByUserIdAndDeletedFalse(UUID userId);

    Optional<PortfolioPosition> findByUserIdAndSymbolAndDeletedFalse(UUID userId, String symbol);

    List<PortfolioPosition> findByStrategyKeyAndDeletedFalse(String strategyKey);

    @Query("""
            select p from PortfolioPosition p
            where p.strategyKey = :strategyKey and p.deleted = false and p.simulation = false
            """)
    List<PortfolioPosition> findRealByStrategyKeyAndDeletedFalse(@Param("strategyKey") String strategyKey);

    /** OMS legs with qty but no entry price — never a real broker position. */
    @Query("""
            select p from PortfolioPosition p
            where p.deleted = false and p.simulation = false
            and p.quantity <> 0
            and (p.avgPrice is null or p.avgPrice = 0)
            """)
    List<PortfolioPosition> findZeroPriceGhostPositions();

    @Query("""
            select p from PortfolioPosition p
            where p.deleted = false and p.simulation = false
            and p.quantity <> 0
            order by p.updatedAt desc
            """)
    List<PortfolioPosition> findAllRealOpenPositions();
}
