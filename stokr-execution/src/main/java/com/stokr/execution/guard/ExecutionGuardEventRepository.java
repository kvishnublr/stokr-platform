package com.stokr.execution.guard;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExecutionGuardEventRepository extends JpaRepository<ExecutionGuardEvent, UUID> {
    List<ExecutionGuardEvent> findByUserIdAndDeletedFalseOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}

