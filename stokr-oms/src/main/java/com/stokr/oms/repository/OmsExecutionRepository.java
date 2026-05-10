package com.stokr.oms.repository;

import com.stokr.oms.domain.OmsExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
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
            where o.backtestRunId = :runId and e.deleted = false and o.deleted = false
            order by e.executionTimestamp asc, e.createdAt asc
            """)
    List<OmsExecution> findAllForBacktestRunOrdered(@Param("runId") UUID runId);

    @Query("""
            select avg(e.latencyMs) from OmsExecution e join e.order o
            where e.deleted = false and o.deleted = false
            and e.latencyMs is not null
            and (:userId is null or o.userId = :userId)
            and (:from is null or e.createdAt >= :from)
            and (:to is null or e.createdAt < :to)
            """)
    Double averageLatencyMs(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    @Query("""
            select avg(e.slippageBps) from OmsExecution e join e.order o
            where e.deleted = false and o.deleted = false
            and e.slippageBps is not null
            and (:userId is null or o.userId = :userId)
            and (:from is null or e.createdAt >= :from)
            and (:to is null or e.createdAt < :to)
            """)
    BigDecimal averageSlippageBps(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );
}
