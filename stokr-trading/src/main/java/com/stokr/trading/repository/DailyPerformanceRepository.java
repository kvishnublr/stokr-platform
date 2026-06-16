package com.stokr.trading.repository;

import com.stokr.trading.domain.DailyPerformance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DailyPerformanceRepository extends JpaRepository<DailyPerformance, UUID> {

    Optional<DailyPerformance> findByInstanceIdAndDate(UUID instanceId, LocalDate date);

    List<DailyPerformance> findByInstanceIdOrderByDateDesc(UUID instanceId);

    List<DailyPerformance> findByInstanceIdAndDateBetweenOrderByDateDesc(UUID instanceId, LocalDate start, LocalDate end);

    @Query("SELECT dp FROM DailyPerformance dp WHERE dp.instanceId = :instanceId ORDER BY dp.date DESC LIMIT :limit")
    List<DailyPerformance> findRecentByInstanceId(UUID instanceId, int limit);

    @Query("SELECT COALESCE(SUM(dp.pnl), 0) FROM DailyPerformance dp WHERE dp.instanceId = :instanceId AND dp.date >= :since")
    java.math.BigDecimal sumPnlSince(UUID instanceId, LocalDate since);

    @Query("SELECT COALESCE(AVG(dp.returns), 0) FROM DailyPerformance dp WHERE dp.instanceId = :instanceId")
    java.math.BigDecimal avgReturns(UUID instanceId);

    List<DailyPerformance> findByInstanceIdAndDateAfterOrderByDateDesc(UUID instanceId, LocalDate after);

    void deleteByInstanceId(UUID instanceId);
}
