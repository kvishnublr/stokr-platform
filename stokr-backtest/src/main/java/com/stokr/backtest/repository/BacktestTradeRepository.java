package com.stokr.backtest.repository;

import com.stokr.backtest.domain.BacktestTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BacktestTradeRepository extends JpaRepository<BacktestTrade, UUID> {

    List<BacktestTrade> findByRun_IdAndDeletedFalseOrderByCreatedAtAsc(UUID runId);

    @Modifying
    @Query("update BacktestTrade t set t.deleted = true where t.run.id = :runId and t.deleted = false")
    int softDeleteForRun(@Param("runId") UUID runId);
}
