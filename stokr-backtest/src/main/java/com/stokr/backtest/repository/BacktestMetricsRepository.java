package com.stokr.backtest.repository;

import com.stokr.backtest.domain.BacktestMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BacktestMetricsRepository extends JpaRepository<BacktestMetrics, UUID> {

    Optional<BacktestMetrics> findByRun_IdAndDeletedFalse(UUID runId);

    @Query("""
            select r.strategyKey, avg(m.sharpeRatio), avg(m.winRate), avg(m.maxDrawdown), count(m)
            from BacktestMetrics m join m.run r
            where r.userId = :userId and m.deleted = false and r.deleted = false
            group by r.strategyKey
            order by avg(m.sharpeRatio) desc
            """)
    List<Object[]> leaderboardRowsRaw(@Param("userId") UUID userId);
}
