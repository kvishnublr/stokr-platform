package io.stokr.oms.repository;

import io.stokr.oms.domain.entity.PositionLifecycleAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PositionLifecycleAuditRepository extends JpaRepository<PositionLifecycleAudit, UUID> {

    List<PositionLifecycleAudit> findByPositionIdOrderByOccurredAtDesc(UUID positionId);

    List<PositionLifecycleAudit> findByUserIdOrderByOccurredAtDesc(UUID userId);

    @Query("SELECT pla FROM PositionLifecycleAudit pla WHERE pla.userId = ?1 AND pla.occurredAt BETWEEN ?2 AND ?3 ORDER BY pla.occurredAt DESC")
    List<PositionLifecycleAudit> findAuditsByUserAndDateRange(UUID userId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT pla FROM PositionLifecycleAudit pla WHERE pla.ownerType = ?1")
    List<PositionLifecycleAudit> findByOwnerType(String ownerType);

    @Query("SELECT pla FROM PositionLifecycleAudit pla WHERE pla.exitSource = ?1")
    List<PositionLifecycleAudit> findByExitSource(String exitSource);
}
