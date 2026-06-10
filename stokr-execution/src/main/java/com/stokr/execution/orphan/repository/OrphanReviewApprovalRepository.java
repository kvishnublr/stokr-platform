package com.stokr.execution.orphan.repository;

import com.stokr.execution.orphan.domain.OrphanReviewApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OrphanReviewApprovalRepository extends JpaRepository<OrphanReviewApproval, UUID> {

    List<OrphanReviewApproval> findByOrphanId(UUID orphanId);

    List<OrphanReviewApproval> findByOperatorId(UUID operatorId);

    @Query("SELECT a FROM OrphanReviewApproval a WHERE a.operatorId = ?1 " +
           "AND a.decisionTimestamp >= ?2 AND a.decisionTimestamp <= ?3 " +
           "ORDER BY a.decisionTimestamp DESC")
    List<OrphanReviewApproval> findOperatorDecisions(UUID operatorId, OffsetDateTime from, OffsetDateTime to);

    @Query("SELECT COUNT(a) FROM OrphanReviewApproval a WHERE a.operatorId = ?1")
    long countApprovalsByOperator(UUID operatorId);
}
