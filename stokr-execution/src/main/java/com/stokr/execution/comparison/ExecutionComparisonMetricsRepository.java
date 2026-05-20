package com.stokr.execution.comparison;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExecutionComparisonMetricsRepository extends JpaRepository<ExecutionComparisonMetrics, UUID> {
    Optional<ExecutionComparisonMetrics> findBySignalIdAndDeletedFalse(UUID signalId);
}
