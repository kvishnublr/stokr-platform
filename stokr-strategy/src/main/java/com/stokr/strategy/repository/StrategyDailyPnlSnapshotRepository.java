package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategyDailyPnlSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface StrategyDailyPnlSnapshotRepository extends JpaRepository<StrategyDailyPnlSnapshot, UUID> {

    Optional<StrategyDailyPnlSnapshot> findByStrategyKeyAndBusinessDateAndDeletedFalse(
            String strategyKey, LocalDate businessDate);

    @Modifying
    @Query("""
            update StrategyDailyPnlSnapshot s
            set s.realizedPnl = s.realizedPnl + :delta, s.tradeCount = s.tradeCount + 1, s.updatedAt = current_timestamp
            where s.strategyKey = :strategyKey and s.businessDate = :date and s.deleted = false
            """)
    int addRealizedPnl(
            @Param("strategyKey") String strategyKey,
            @Param("date") LocalDate date,
            @Param("delta") BigDecimal delta);
}
