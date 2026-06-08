package com.stokr.oms.repository;

import com.stokr.oms.domain.OmsExecution;
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

public interface OmsExecutionRepository extends JpaRepository<OmsExecution, UUID>, JpaSpecificationExecutor<OmsExecution> {

    List<OmsExecution> findByOrder_IdOrderByCreatedAtAsc(UUID orderId);

    Optional<OmsExecution> findTopByOrder_IdOrderByExecutionSequenceDesc(UUID orderId);

    boolean existsByExecutionHashAndDeletedFalse(String executionHash);

    boolean existsByBrokerExecutionIdAndDeletedFalse(String brokerExecutionId);

    @Query("""
            select e from OmsExecution e
            join fetch e.order o
            where o.userId = :userId and e.deleted = false and o.deleted = false
            order by e.executionTimestamp asc, e.createdAt asc
            """)
    List<OmsExecution> findAllForUserOrdered(@Param("userId") UUID userId);

    @Query("""
            select e from OmsExecution e
            join fetch e.order o
            where o.userId = :userId and o.symbol = :symbol and e.deleted = false and o.deleted = false
            order by e.executionTimestamp asc, e.createdAt asc
            """)
    List<OmsExecution> findAllForUserAndSymbolOrdered(@Param("userId") UUID userId, @Param("symbol") String symbol);

    @Query("""
            select e from OmsExecution e
            join fetch e.order o
            where o.userId = :userId
              and o.symbol = :symbol
              and e.deleted = false
              and o.deleted = false
              and o.executionMode = com.stokr.oms.domain.ExecutionMode.LIVE
              and o.testTrade = false
              and o.backtestRunId is null
            order by e.executionTimestamp asc, e.createdAt asc
            """)
    List<OmsExecution> findLiveForUserAndSymbolOrdered(@Param("userId") UUID userId, @Param("symbol") String symbol);

    @Query("""
            select e from OmsExecution e
            join fetch e.order o
            where o.backtestRunId = :runId and e.deleted = false and o.deleted = false
            order by e.executionTimestamp asc, e.createdAt asc
            """)
    List<OmsExecution> findAllForBacktestRunOrdered(@Param("runId") UUID runId);

    @Query("""
            select avg(e.latencyMs) from OmsExecution e join e.order o
            where e.deleted = false and o.deleted = false
            and e.latencyMs is not null
            and (:userId is null or o.userId = :userId)
            and e.createdAt >= :from
            and e.createdAt < :to
            """)
    Double averageLatencyMs(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    @Query("""
            select avg(e.latencyMs) from OmsExecution e join e.order o
            where e.deleted = false and o.deleted = false
            and e.latencyMs is not null
            """)
    Double averageLatencyMsAll();

    @Query("""
            select avg(e.slippageBps) from OmsExecution e join e.order o
            where e.deleted = false and o.deleted = false
            and e.slippageBps is not null
            and (:userId is null or o.userId = :userId)
            and e.createdAt >= :from
            and e.createdAt < :to
            """)
    BigDecimal averageSlippageBps(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    @Query("""
            select o.signalId, e.avgPrice from OmsExecution e
            join e.order o
            where o.signalId in :signalIds and e.deleted = false and o.deleted = false
            """)
    List<Object[]> findAvgPriceBySignalIds(@Param("signalIds") Collection<UUID> signalIds);

    @Query(value = """
            SELECT
                o.symbol AS symbol,
                COALESCE(SUM(
                    CASE
                        WHEN UPPER(o.side) = 'BUY' THEN e.filled_qty
                        ELSE -e.filled_qty
                    END
                ), 0) AS net_qty
            FROM oms_executions e
            JOIN oms_orders o ON o.id = e.order_id
            WHERE o.deleted = FALSE
              AND e.deleted = FALSE
              AND o.user_id = :userId
              AND o.execution_mode = 'LIVE'
              AND COALESCE(o.is_test_trade, FALSE) = FALSE
              AND o.backtest_run_id IS NULL
            GROUP BY o.symbol
            HAVING COALESCE(SUM(
                    CASE
                        WHEN UPPER(o.side) = 'BUY' THEN e.filled_qty
                        ELSE -e.filled_qty
                    END
                ), 0) <> 0
            """, nativeQuery = true)
    List<Object[]> computeLiveNetQtyBySymbol(@Param("userId") UUID userId);
}
