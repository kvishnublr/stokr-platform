package com.stokr.oms.repository;

import com.stokr.common.simulation.AnalyticsDataScope;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.Modifying;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OmsOrderRepository extends JpaRepository<OmsOrder, UUID>, JpaSpecificationExecutor<OmsOrder> {

    long countByDeletedFalse();

    long countByDeletedFalseAndState(OrderState state);

    long countByDeletedFalseAndCreatedAtGreaterThanEqual(@Param("since") Instant since);

    @Query("""
            select count(o) from OmsOrder o
            where o.deleted = false
            and o.state in :states
            and o.updatedAt < :before
            """)
    long countStuckOrders(@Param("states") Collection<OrderState> states, @Param("before") Instant before);

    @Query("""
            select o from OmsOrder o
            where o.deleted = false
            and o.state in :states
            and o.updatedAt < :before
            order by o.updatedAt asc
            """)
    List<OmsOrder> findStuckOrders(@Param("states") Collection<OrderState> states, @Param("before") Instant before);

    Optional<OmsOrder> findByUserIdAndIdempotencyKeyAndDeletedFalse(UUID userId, String idempotencyKey);

    long countByUserIdAndDeletedFalse(UUID userId);

    long countByUserIdAndDeletedFalseAndState(UUID userId, OrderState state);

    long countByUserIdAndDeletedFalseAndBacktestRunIdIsNullAndStateIn(UUID userId, Collection<OrderState> states);

    List<OmsOrder> findAllByUserIdAndDeletedFalseAndStateIn(UUID userId, Collection<OrderState> states);

    @Query("""
            select count(o) from OmsOrder o
            where o.userId = :userId and o.deleted = false and o.backtestRunId is null
            and o.createdAt >= :start and o.createdAt < :end
            """)
    long countByUserAndDayNonBacktest(@Param("userId") UUID userId, @Param("start") Instant start, @Param("end") Instant end);

    @Query("""
            select max(o.createdAt) from OmsOrder o
            where o.userId = :userId and o.symbol = :symbol and o.deleted = false
            and (:excludeId is null or o.id <> :excludeId)
            """)
    Optional<Instant> findLatestCreatedAtForUserSymbolExcluding(
            @Param("userId") UUID userId,
            @Param("symbol") String symbol,
            @Param("excludeId") UUID excludeId
    );

    @Query("""
            select count(o) from OmsOrder o
            where o.userId = :userId and o.deleted = false and o.backtestRunId is null
            and o.createdAt >= :since and o.id <> :excludeId
            """)
    long countNonBacktestOrdersSinceExcluding(
            @Param("userId") UUID userId,
            @Param("since") Instant since,
            @Param("excludeId") UUID excludeId
    );

    @Query("""
            select count(o) from OmsOrder o
            where o.userId = :userId and o.symbol = :symbol and o.side = :side and o.deleted = false
            and o.backtestRunId is null and o.id <> :excludeId and o.state in :states
            """)
    long countActiveSameDirection(
            @Param("userId") UUID userId,
            @Param("symbol") String symbol,
            @Param("side") String side,
            @Param("excludeId") UUID excludeId,
            @Param("states") Collection<OrderState> states
    );

    @Query("""
            select coalesce(sum(o.quantity * coalesce(o.limitPrice, o.entryReferencePrice, 0)), 0)
            from OmsOrder o
            where o.userId = :userId and o.strategyKey = :strategyKey and o.deleted = false
            and o.backtestRunId is null and o.simulation = false and o.id <> :excludeId and o.state in :states
            """)
    BigDecimal sumOpenNotionalExcluding(
            @Param("userId") UUID userId,
            @Param("strategyKey") String strategyKey,
            @Param("excludeId") UUID excludeId,
            @Param("states") Collection<OrderState> states
    );

    @Query("""
            select o from OmsOrder o
            where o.deleted = false and o.state in :states
            order by o.updatedAt desc
            """)
    List<OmsOrder> findRecentByStateIn(@Param("states") Collection<OrderState> states, Pageable pageable);

    List<OmsOrder> findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(UUID signalId);

    @Query("""
            select o from OmsOrder o
            where o.deleted = false
              and o.signalId is not null
              and o.state in :states
              and o.side in ('BUY', 'SELL')
              and o.createdAt >= :since
            order by o.createdAt desc
            """)
    List<OmsOrder> findRecentFilledEntriesWithSignal(
            @Param("since") Instant since,
            @Param("states") Collection<OrderState> states,
            Pageable pageable);

    @Query("""
            select case when count(o) > 0 then true else false end
            from OmsOrder o
            where o.deleted = false
              and o.userId = :userId
              and o.symbol = :symbol
              and o.side = :exitSide
              and o.createdAt > :entryAt
              and o.state in :states
            """)
    boolean existsOppositeSideAfter(
            @Param("userId") UUID userId,
            @Param("symbol") String symbol,
            @Param("exitSide") String exitSide,
            @Param("entryAt") Instant entryAt,
            @Param("states") Collection<OrderState> states);

    long countBySimulationRunIdAndDeletedFalse(UUID simulationRunId);

    Optional<OmsOrder> findFirstBySignalIdAndUserIdAndDeletedFalseOrderByCreatedAtDesc(UUID signalId, UUID userId);

    @Query("""
            select o from OmsOrder o
            where o.deleted = false and o.backtestRunId is null
            and o.executionMode = com.stokr.oms.domain.ExecutionMode.LIVE
            and o.state in :states
            """)
    List<OmsOrder> findAllLiveActiveOrders(@Param("states") Collection<OrderState> states);

    @Query("""
            select count(o) from OmsOrder o
            where o.userId = :userId and o.strategyKey = :strategyKey and o.deleted = false
            and o.backtestRunId is null and o.id <> :excludeId and o.state in :states
            """)
    long countOpenOrdersByStrategyKey(
            @Param("userId") UUID userId,
            @Param("strategyKey") String strategyKey,
            @Param("excludeId") UUID excludeId,
            @Param("states") Collection<OrderState> states);

    @Query("""
            select max(o.createdAt) from OmsOrder o
            where o.userId = :userId and o.strategyKey = :strategyKey and o.deleted = false
            and o.backtestRunId is null and o.id <> :excludeId
            """)
    java.util.Optional<java.time.Instant> findLatestCreatedAtForStrategyExcluding(
            @Param("userId") UUID userId,
            @Param("strategyKey") String strategyKey,
            @Param("excludeId") UUID excludeId);

    @Query(value = """
            SELECT reject_reason, COUNT(*)::bigint AS cnt
            FROM oms_orders
            WHERE deleted = FALSE AND backtest_run_id IS NULL
              AND state = 'REJECTED' AND reject_reason IS NOT NULL
              AND (CAST(:since AS timestamptz) IS NULL OR created_at >= CAST(:since AS timestamptz))
              AND (
                :scope = 'MIXED'
                OR (:scope = 'SIMULATION' AND is_simulation = TRUE)
                OR (:scope = 'REAL' AND is_simulation = FALSE AND is_test_trade = FALSE)
              )
            GROUP BY reject_reason
            ORDER BY cnt DESC
            LIMIT 20
            """, nativeQuery = true)
    List<Object[]> countRejectionsByReason(@Param("scope") String scope, @Param("since") Instant since);

    default List<Object[]> countRejectionsByReason() {
        return countRejectionsByReason(AnalyticsDataScope.REAL.name(), null);
    }

    default List<Object[]> countRejectionsByReason(Instant since) {
        return countRejectionsByReason(AnalyticsDataScope.REAL.name(), since);
    }

    default List<Object[]> countRejectionsByReason(String scope) {
        return countRejectionsByReason(scope, null);
    }

    @Query(value = """
            SELECT
                COUNT(*) FILTER (WHERE created_at >= :since)::bigint                                                          AS total_today,
                COUNT(*) FILTER (WHERE created_at >= :since AND state = 'FILLED')::bigint                                     AS filled_today,
                COUNT(*) FILTER (WHERE created_at >= :since AND state = 'REJECTED')::bigint                                   AS rejected_today,
                COUNT(*) FILTER (WHERE created_at >= :since AND state = 'PARTIALLY_FILLED')::bigint                           AS partial_today,
                COUNT(*) FILTER (WHERE created_at >= :since AND state IN ('CANCELLED','EXPIRED'))::bigint                     AS cancelled_today,
                COUNT(*) FILTER (WHERE created_at >= :since AND state IN ('CREATED','VALIDATED','SUBMITTED','ACCEPTED'))::bigint AS pending_today,
                COUNT(*)::bigint                                                                                                AS total_all_time
            FROM oms_orders
            WHERE deleted = FALSE AND backtest_run_id IS NULL
              AND (
                :scope = 'MIXED'
                OR (:scope = 'SIMULATION' AND is_simulation = TRUE)
                OR (:scope = 'REAL' AND is_simulation = FALSE AND is_test_trade = FALSE)
              )
            """, nativeQuery = true)
    List<Object[]> computeStats(@Param("since") Instant since, @Param("scope") String scope);

    default List<Object[]> computeStats(Instant since) {
        return computeStats(since, AnalyticsDataScope.REAL.name());
    }

    @Query("""
            select o from OmsOrder o
            where o.userId = :userId and o.executionMode = :executionMode and o.deleted = false
            and o.createdAt >= :startTime and o.createdAt < :endTime
            """)
    List<OmsOrder> findByUserIdAndExecutionModeAndCreatedAtBetweenAndDeletedFalse(
            @Param("userId") UUID userId,
            @Param("executionMode") com.stokr.oms.domain.ExecutionMode executionMode,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime
    );

    @Query("""
            select coalesce(sum(o.quantity * coalesce(o.entryReferencePrice, o.limitPrice, 0)), 0)
            from OmsOrder o
            where o.strategyKey = :strategyKey and o.deleted = false and o.backtestRunId is null
            and o.simulation = false and o.state in :states
            """)
    BigDecimal sumPendingNotionalByStrategy(
            @Param("strategyKey") String strategyKey,
            @Param("states") Collection<OrderState> states);

    @Modifying
    @Query("""
            update OmsOrder o set o.deleted = true
            where o.userId = :userId and o.symbol = :symbol and o.deleted = false
            """)
    void softDeleteGhostOrders(
            @Param("userId") UUID userId,
            @Param("symbol") String symbol);
}
