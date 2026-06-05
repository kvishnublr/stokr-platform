package com.stokr.bootstrap.service;

import com.stokr.bootstrap.domain.entity.StrategyDriftDetectionLog;
import com.stokr.bootstrap.repository.StrategyDriftDetectionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyDriftMonitor {

    private final StrategyDriftDetectionLogRepository driftLogRepository;

    @Scheduled(fixedRate = 30000)  // Every 30 seconds
    @Transactional
    public void monitorStrategyDrift() {
        log.debug("Checking for strategy drift...");

        // TODO: For each active strategy:
        // 1. Get expected entry conditions from strategy_definition
        // 2. Get actual market conditions
        // 3. Compare if conditions match expectations
        // 4. Same for exit conditions
        // 5. Check if position count matches expected count
        // 6. Alert if drift detected
    }

    @Transactional
    public void recordDrift(UUID userId, String strategyName,
                           boolean entryDriftDetected, boolean exitDriftDetected,
                           int expectedPositions, int actualPositions) {

        int positionDelta = actualPositions - expectedPositions;
        String severity = "LOW";
        if (Math.abs(positionDelta) >= 5) severity = "HIGH";
        else if (Math.abs(positionDelta) >= 2) severity = "MEDIUM";

        var driftLog = StrategyDriftDetectionLog.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .strategyName(strategyName)
            .entryDriftDetected(entryDriftDetected)
            .exitDriftDetected(exitDriftDetected)
            .expectedOpenPositions(expectedPositions)
            .actualOpenPositions(actualPositions)
            .positionDelta(positionDelta)
            .driftSeverity(severity)
            .alertSent(true)
            .checkTime(LocalDateTime.now())
            .build();

        driftLogRepository.save(driftLog);

        if (entryDriftDetected || exitDriftDetected) {
            log.warn("STRATEGY_DRIFT_DETECTED: {} - entry_drift={} exit_drift={} severity={}",
                strategyName, entryDriftDetected, exitDriftDetected, severity);

            if ("HIGH".equals(severity)) {
                pauseStrategy(userId, strategyName, "High drift detected");
            }
        }
    }

    private void pauseStrategy(UUID userId, String strategyName, String reason) {
        log.error("PAUSING STRATEGY: {} for user {} - {}", strategyName, userId, reason);
        // TODO: Call ExitAllService or StrategyPauseService to pause
    }

    public boolean isDriftingHigh(UUID userId, String strategyName) {
        // TODO: Query most recent drift log for this strategy
        // Return true if severity is HIGH
        return false;
    }
}
