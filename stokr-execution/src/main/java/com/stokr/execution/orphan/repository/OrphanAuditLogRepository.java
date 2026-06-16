package com.stokr.execution.orphan.repository;

import com.stokr.execution.orphan.domain.OrphanAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OrphanAuditLogRepository extends JpaRepository<OrphanAuditLog, Long> {

    List<OrphanAuditLog> findByOrphanIdOrderByCreatedAtAsc(UUID orphanId);

    List<OrphanAuditLog> findByEventType(String eventType);

    List<OrphanAuditLog> findByActorId(UUID actorId);

    @Query("SELECT a FROM OrphanAuditLog a WHERE a.createdAt < ?1 ORDER BY a.createdAt ASC")
    List<OrphanAuditLog> findExpiredLogs(Instant retentionThreshold);

    @Query("SELECT a FROM OrphanAuditLog a WHERE a.orphanId = ?1 AND a.eventType = ?2 ORDER BY a.createdAt DESC")
    List<OrphanAuditLog> findEventsByTypeForOrphan(UUID orphanId, String eventType);

    @Query("SELECT COUNT(a) FROM OrphanAuditLog a WHERE a.eventType = ?1")
    long countEventsByType(String eventType);

    @Query("SELECT a FROM OrphanAuditLog a WHERE a.eventType = 'OPERATOR_APPROVAL' AND a.actorId = ?1 AND a.createdAt BETWEEN ?2 AND ?3 ORDER BY a.createdAt DESC")
    List<OrphanAuditLog> findOperatorDecisions(UUID operatorId, Instant from, Instant to);
}
