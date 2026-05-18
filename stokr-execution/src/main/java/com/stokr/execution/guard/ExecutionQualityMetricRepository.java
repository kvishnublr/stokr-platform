package com.stokr.execution.guard;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionQualityMetricRepository extends JpaRepository<ExecutionQualityMetric, UUID> {
    List<ExecutionQualityMetric> findByUserIdAndDeletedFalseOrderByCreatedAtDesc(UUID userId, Pageable pageable);
    Optional<ExecutionQualityMetric> findFirstByOrderIdAndDeletedFalse(UUID orderId);
}

