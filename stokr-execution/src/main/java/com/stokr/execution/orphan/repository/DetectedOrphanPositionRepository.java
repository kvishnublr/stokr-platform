package com.stokr.execution.orphan.repository;

import com.stokr.execution.orphan.domain.DetectedOrphanPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DetectedOrphanPositionRepository extends JpaRepository<DetectedOrphanPosition, UUID> {

    List<DetectedOrphanPosition> findByUserIdAndSymbol(UUID userId, String symbol);

    List<DetectedOrphanPosition> findByUserIdOrderByDetectionTimestampDesc(UUID userId);

    @Query("SELECT d FROM DetectedOrphanPosition d WHERE d.status IN ('DETECTED', 'CLASSIFIED', 'REVIEWED') " +
           "ORDER BY d.detectionTimestamp DESC")
    List<DetectedOrphanPosition> findPendingOrphans();

    @Query("SELECT d FROM DetectedOrphanPosition d WHERE d.status = 'DO_NOT_TOUCH' ORDER BY d.detectionTimestamp DESC")
    List<DetectedOrphanPosition> findDoNotTouchPositions();

    List<DetectedOrphanPosition> findByDetectionTimestampAfter(Instant timestamp);

    Optional<DetectedOrphanPosition> findByUserIdAndSymbolAndBrokerEntryTime(UUID userId, String symbol, Instant brokerEntryTime);
}
