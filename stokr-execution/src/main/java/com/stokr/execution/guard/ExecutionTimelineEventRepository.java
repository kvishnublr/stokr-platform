package com.stokr.execution.guard;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExecutionTimelineEventRepository extends JpaRepository<ExecutionTimelineEvent, UUID> {
    List<ExecutionTimelineEvent> findByTimelineIdAndDeletedFalseOrderByEventTimeAsc(UUID timelineId);
    List<ExecutionTimelineEvent> findByUserIdAndDeletedFalseOrderByCreatedAtDesc(UUID userId, Pageable pageable);
    List<ExecutionTimelineEvent> findBySignalIdAndDeletedFalseOrderByEventTimeAsc(UUID signalId);
}

