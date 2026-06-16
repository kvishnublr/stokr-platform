package com.stokr.common.simulation.repository;

import com.stokr.common.simulation.domain.SimulationRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SimulationRunRepository extends JpaRepository<SimulationRunEntity, UUID> {

    List<SimulationRunEntity> findTop50ByDeletedFalseOrderByStartedAtDesc();

    List<SimulationRunEntity> findByDeletedFalseAndScenarioOrderByStartedAtDesc(String scenario);

    List<SimulationRunEntity> findByDeletedFalseAndStartedAtBetweenOrderByStartedAtDesc(
            Instant from, Instant to);

    @Modifying
    @Query(value = """
            UPDATE strategy_signals SET deleted = TRUE, updated_at = NOW()
            WHERE is_simulation = TRUE AND deleted = FALSE
              AND (:runId IS NULL OR simulation_run_id = :runId)
              AND (:scenario IS NULL OR simulation_scenario = :scenario)
              AND (:fromTs IS NULL OR created_at >= :fromTs)
              AND (:toTs IS NULL OR created_at < :toTs)
            """, nativeQuery = true)
    int softDeleteSignals(
            @Param("runId") UUID runId,
            @Param("scenario") String scenario,
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs);

    @Modifying
    @Query(value = """
            UPDATE oms_orders SET deleted = TRUE, updated_at = NOW()
            WHERE is_simulation = TRUE AND deleted = FALSE
              AND (:runId IS NULL OR simulation_run_id = :runId)
              AND (:scenario IS NULL OR simulation_scenario = :scenario)
              AND (:fromTs IS NULL OR created_at >= :fromTs)
              AND (:toTs IS NULL OR created_at < :toTs)
            """, nativeQuery = true)
    int softDeleteOrders(
            @Param("runId") UUID runId,
            @Param("scenario") String scenario,
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs);

    @Modifying
    @Query(value = """
            UPDATE oms_executions e SET deleted = TRUE, updated_at = NOW()
            FROM oms_orders o
            WHERE e.order_id = o.id AND e.is_simulation = TRUE AND e.deleted = FALSE
              AND (:runId IS NULL OR e.simulation_run_id = :runId)
              AND (:scenario IS NULL OR e.simulation_scenario = :scenario)
              AND (:fromTs IS NULL OR e.created_at >= :fromTs)
              AND (:toTs IS NULL OR e.created_at < :toTs)
            """, nativeQuery = true)
    int softDeleteExecutions(
            @Param("runId") UUID runId,
            @Param("scenario") String scenario,
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs);

    @Modifying
    @Query(value = """
            UPDATE portfolio_positions SET deleted = TRUE, updated_at = NOW()
            WHERE is_simulation = TRUE AND deleted = FALSE
              AND (:runId IS NULL OR simulation_run_id = :runId)
              AND (:scenario IS NULL OR simulation_scenario = :scenario)
              AND (:fromTs IS NULL OR created_at >= :fromTs)
              AND (:toTs IS NULL OR created_at < :toTs)
            """, nativeQuery = true)
    int softDeletePositions(
            @Param("runId") UUID runId,
            @Param("scenario") String scenario,
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs);

    @Modifying
    @Query("""
            UPDATE SimulationRunEntity r SET r.deleted = true
            WHERE r.deleted = false
              AND (:runId IS NULL OR r.id = :runId)
              AND (:scenario IS NULL OR r.scenario = :scenario)
              AND (:fromTs IS NULL OR r.startedAt >= :fromTs)
              AND (:toTs IS NULL OR r.startedAt < :toTs)
            """)
    int softDeleteRuns(
            @Param("runId") UUID runId,
            @Param("scenario") String scenario,
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs);
}
