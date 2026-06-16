package com.stokr.execution.comparison;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionComparisonMetricsRepository extends JpaRepository<ExecutionComparisonMetrics, UUID> {

    Optional<ExecutionComparisonMetrics> findBySignalIdAndDeletedFalse(UUID signalId);

    List<ExecutionComparisonMetrics> findByStrategyKeyAndDeletedFalseOrderByCreatedAtDesc(
            String strategyKey, Pageable pageable);

    long countByStrategyKeyAndReconciliationStatusAndDeletedFalse(String strategyKey, String reconciliationStatus);

    long countByReconciliationStatusAndDeletedFalse(String reconciliationStatus);

    @Query("""
            select m from ExecutionComparisonMetrics m
            where m.deleted = false
            and m.reconciliationStatus <> 'RECONCILED'
            and m.createdAt < :before
            order by m.createdAt asc
            """)
    List<ExecutionComparisonMetrics> findStaleUnreconciled(@Param("before") Instant before, Pageable pageable);

    @Query("""
            select m from ExecutionComparisonMetrics m
            where m.deleted = false
            and m.strategyKey = :strategyKey
            and m.reconciledAt >= :since
            order by m.reconciledAt desc
            """)
    List<ExecutionComparisonMetrics> findReconciledSince(
            @Param("strategyKey") String strategyKey,
            @Param("since") Instant since);
}
