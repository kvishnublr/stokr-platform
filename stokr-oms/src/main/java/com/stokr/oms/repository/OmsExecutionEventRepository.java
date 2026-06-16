package com.stokr.oms.repository;

import com.stokr.oms.domain.ExecutionEventType;
import com.stokr.oms.domain.OmsExecutionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface OmsExecutionEventRepository extends JpaRepository<OmsExecutionEvent, UUID> {

    @Query("select coalesce(max(e.streamSequence), 0) from OmsExecutionEvent e where e.order.id = :orderId and e.deleted = false")
    long maxStreamSequence(@Param("orderId") UUID orderId);

    long countByBacktestRunIdAndEventTypeAndDeletedFalse(UUID backtestRunId, ExecutionEventType eventType);

    java.util.List<OmsExecutionEvent> findByOrder_IdAndDeletedFalseOrderByStreamSequenceAsc(UUID orderId);
}
