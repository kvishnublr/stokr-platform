package com.stokr.oms.reconciliation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReconciliationEventRepository extends JpaRepository<ReconciliationEvent, UUID> {

    List<ReconciliationEvent> findAllByDeletedFalseAndStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    List<ReconciliationEvent> findAllByDeletedFalseOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            select e from ReconciliationEvent e
            where e.deleted = false
              and e.userId = :userId
              and e.discrepancyType = 'ORPHAN_BROKER_POSITION'
              and e.createdAt >= :since
              and e.brokerQty is not null
              and e.brokerQty <> 0
            order by e.createdAt desc
            """)
    List<ReconciliationEvent> findRecentOrphanBrokerPositions(
            @Param("userId") UUID userId,
            @Param("since") Instant since
    );
}
