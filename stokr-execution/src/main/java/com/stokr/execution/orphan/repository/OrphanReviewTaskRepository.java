package com.stokr.execution.orphan.repository;

import com.stokr.execution.orphan.domain.OrphanReviewTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrphanReviewTaskRepository extends JpaRepository<OrphanReviewTask, UUID> {

    List<OrphanReviewTask> findByStatus(String status);

    List<OrphanReviewTask> findByAssignedOperatorId(UUID operatorId);

    @Query("SELECT t FROM OrphanReviewTask t WHERE t.assignedOperatorId = ?1 AND t.status = 'PENDING_REVIEW' " +
           "ORDER BY t.priority DESC, t.dueDate ASC")
    List<OrphanReviewTask> findPendingTasksForOperator(UUID operatorId);

    @Query("SELECT t FROM OrphanReviewTask t WHERE t.status = 'PENDING_REVIEW' ORDER BY t.priority DESC, t.dueDate ASC")
    List<OrphanReviewTask> findAllPendingTasks();

    @Query("SELECT t FROM OrphanReviewTask t WHERE t.status IN ('PENDING_REVIEW', 'DO_NOT_TOUCH') " +
           "AND t.dueDate < ?1 ORDER BY t.dueDate ASC")
    List<OrphanReviewTask> findStaleTasks(OffsetDateTime threshold);

    Optional<OrphanReviewTask> findByOrphanId(UUID orphanId);
}
