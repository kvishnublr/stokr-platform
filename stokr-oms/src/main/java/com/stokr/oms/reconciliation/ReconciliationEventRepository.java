package com.stokr.oms.reconciliation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReconciliationEventRepository extends JpaRepository<ReconciliationEvent, UUID> {

    List<ReconciliationEvent> findAllByDeletedFalseAndStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    List<ReconciliationEvent> findAllByDeletedFalseOrderByCreatedAtDesc(Pageable pageable);
}
