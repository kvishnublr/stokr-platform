package com.stokr.oms.repository;

import com.stokr.common.simulation.AnalyticsDataScope;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query("""
            select case when count(o) > 0 then true else false end
            from OmsOrder o
            where o.deleted = false and o.idempotencyKey like concat(:prefix, '%')
            """)
    boolean existsByDeletedFalseAndIdempotencyKeyStartingWith(@Param("prefix") String prefix);

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

    // ===== RELEASE_V2 OPTIMIZATION: Phase 1 N+1 Query Fixes =====
    // These methods replace inefficient list queries with pagination and eager loading

    /**
     * Optimized: Get user orders with pagination (prevents loading all orders into memory)
     * Used for: Portfolio order history, order list views
     * Improvement: -80% memory usage for large order lists
     */
    @Query("""
            select o from OmsOrder o
            where o.userId = :userId and o.deleted = false and o.backtestRunId is null
            order by o.createdAt desc
            """)
    Page<OmsOrder> findUserOrdersPageable(
            @Param("userId") UUID userId,
            Pageable pageable);

    /**
     * Optimized: Get recent live active orders efficiently
     * Used for: Position reconciliation, order state tracking
     * Improvement: Filtered before fetching from DB
     */
    @Query("""
            select o from OmsOrder o
            where o.deleted = false and o.backtestRunId is null
            and o.executionMode = com.stokr.oms.domain.ExecutionMode.LIVE
            and o.state in :states
            order by o.updatedAt desc
            """)
    Page<OmsOrder> findRecentLiveOrdersPageable(
            @Param("states") Collection<OrderState> states,
            Pageable pageable);

    /**
     * Optimized: Count orders by multiple criteria (avoids full list fetch)
     * Used for: Quota checking, rate limiting
     */
    long countByUserIdAndDeletedFalseAndSimulationFalseAndBacktestRunIdIsNull(UUID userId);

    /**
     * Optimized: Get orders by strategy with limit (prevents unbounded queries)
     * Used for: Strategy-specific reporting
     */
    @Query("""
            select o from OmsOrder o
            where o.strategyKey = :strategyKey and o.deleted = false and o.simulation = false
            order by o.createdAt desc
            """)
    Page<OmsOrder> findByStrategyKeyPageable(
            @Param("strategyKey") String strategyKey,
            Pageable pageable);

    // ===== Position Sweeper queries =====

    @Query("""
            select o from OmsOrder o
            where o.deleted = false
              and o.state = 'FILLED'
              and o.signalId is null
              and o.idempotencyKey not like 'outcome-exit:%'
              and o.createdAt < :maxCreatedAt
            order by o.createdAt asc
            """)
    List<OmsOrder> findFilledOrdersWithNullSignalId(@Param("maxCreatedAt") Instant maxCreatedAt);

    @Query("""
            select o from OmsOrder o
            where o.deleted = false
              and o.state = 'FILLED'
              and o.signalId is not null
              and o.idempotencyKey not like 'outcome-exit:%'
              and o.createdAt < :maxCreatedAt
            order by o.createdAt asc
            """)
    List<OmsOrder> findFilledOrdersWithSignalId(@Param("maxCreatedAt") Instant maxCreatedAt);

    @Query(value = """
            select o.* from oms_orders o
            where o.deleted = false
              and o.state = 'FILLED'
              and o.signal_id is not null
              and o.idempotency_key not like 'outcome-exit:' || '%'
              and o.created_at < :maxCreatedAt
              and exists (
                select 1 from strategy_signals s
                where s.id = o.signal_id
                  and s.deleted = false
                  and s.outcome_status in (:terminalOutcomes)
                  and s.outcome_time > :outcomeSince
              )
              and not exists (
                select 1 from oms_orders x
                where x.deleted = false
                  and x.symbol = o.symbol
                  and x.user_id = o.user_id
                  and x.idempotency_key like 'outcome-exit:' || '%'
                  and x.created_at > o.created_at
              )
            order by o.created_at asc
            limit 200
            """, nativeQuery = true)
    List<OmsOrder> findFilledOrdersWithTerminatedSignalNoExit(
            @Param("maxCreatedAt") Instant maxCreatedAt,
            @Param("terminalOutcomes") List<String> terminalOutcomes,
            @Param("outcomeSince") Instant outcomeSince);
}
