package io.stokr.bootstrap.repository;

import io.stokr.bootstrap.domain.entity.StrategyDriftDetectionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StrategyDriftDetectionLogRepository extends JpaRepository<StrategyDriftDetectionLog, UUID> {

    @Query("SELECT sdl FROM StrategyDriftDetectionLog sdl WHERE sdl.userId = ?1 AND sdl.strategyName = ?2 ORDER BY sdl.checkTime DESC LIMIT 1")
    Optional<StrategyDriftDetectionLog> findLatestDriftFor(UUID userId, String strategyName);

    List<StrategyDriftDetectionLog> findByUserIdAndStrategyNameOrderByCheckTimeDesc(UUID userId, String strategyName);

    @Query("SELECT sdl FROM StrategyDriftDetectionLog sdl WHERE sdl.driftSeverity = 'HIGH'")
    List<StrategyDriftDetectionLog> findHighSeverityDrifts();

    @Query("SELECT sdl FROM StrategyDriftDetectionLog sdl WHERE (sdl.entryDriftDetected = true OR sdl.exitDriftDetected = true)")
    List<StrategyDriftDetectionLog> findAllDrifts();
}
