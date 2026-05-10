package com.stokr.backtest.repository;

import com.stokr.backtest.domain.BacktestEquityCurvePoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BacktestEquityCurveRepository extends JpaRepository<BacktestEquityCurvePoint, UUID> {

    List<BacktestEquityCurvePoint> findByRun_IdAndDeletedFalseOrderByPointTimeAsc(UUID runId);

    @Modifying
    @Query("update BacktestEquityCurvePoint c set c.deleted = true where c.run.id = :runId and c.deleted = false")
    int softDeleteForRun(@Param("runId") UUID runId);
}
