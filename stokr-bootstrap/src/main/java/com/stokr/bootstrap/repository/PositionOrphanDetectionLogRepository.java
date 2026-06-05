package com.stokr.bootstrap.repository;

import com.stokr.bootstrap.domain.entity.PositionOrphanDetectionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PositionOrphanDetectionLogRepository extends JpaRepository<PositionOrphanDetectionLog, UUID> {

    @Query("SELECT podl FROM PositionOrphanDetectionLog podl WHERE podl.isOrphan = true")
    List<PositionOrphanDetectionLog> findAllOrphans();

    @Query("SELECT podl FROM PositionOrphanDetectionLog podl WHERE podl.userId = ?1 AND podl.checkTime > ?2")
    List<PositionOrphanDetectionLog> findOrphansForUserSince(UUID userId, LocalDateTime since);

    @Query("SELECT podl FROM PositionOrphanDetectionLog podl WHERE podl.syntheticExitCreated = false AND podl.isOrphan = true")
    List<PositionOrphanDetectionLog> findUnresolvedOrphans();

    List<PositionOrphanDetectionLog> findByPositionIdOrderByCheckTimeDesc(UUID positionId);
}
