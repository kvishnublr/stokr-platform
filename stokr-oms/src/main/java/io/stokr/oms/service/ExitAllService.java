package io.stokr.oms.service;

import io.stokr.oms.domain.entity.*;
import io.stokr.oms.repository.StrategyPauseStateRepository;
import io.stokr.oms.repository.PositionLifecycleAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class ExitAllService {

    private final StrategyPauseStateRepository pauseStateRepository;
    private final PositionLifecycleAuditRepository auditRepository;

    @Transactional
    public void triggerExitAll(UUID userId, UUID strategyId, String strategyName, String reason) {
        log.info("EXIT_ALL triggered for user={} strategy={} reason={}", userId, strategyName, reason);

        // Fetch or create pause state
        var pauseState = pauseStateRepository.findByUserIdAndStrategyName(userId, strategyName)
            .orElseGet(() -> StrategyPauseState.builder()
                .userId(userId)
                .strategyName(strategyName)
                .currentState("RUNNING")
                .survivesRestart(true)
                .survivesDeployment(true)
                .build());

        // Set to EXIT_ALL_PAUSED
        pauseState.setCurrentState("EXIT_ALL_PAUSED");
        pauseState.setPauseReason(reason);
        pauseState.setPausedAt(LocalDateTime.now());
        pauseState.setResumeCondition("manual_resume_required");

        pauseStateRepository.save(pauseState);

        log.info("EXIT_ALL_PAUSED state persisted for {}. Survives restart={}, deployment={}",
            strategyName, pauseState.getSurvivesRestart(), pauseState.getSurvivesDeployment());
    }

    @Transactional
    public void resumeStrategy(UUID userId, String strategyName) {
        var pauseState = pauseStateRepository.findByUserIdAndStrategyName(userId, strategyName)
            .orElseThrow(() -> new IllegalArgumentException("Pause state not found"));

        pauseState.setCurrentState("RUNNING");
        pauseState.setResumeAt(LocalDateTime.now());
        pauseState.setResumeCondition(null);

        pauseStateRepository.save(pauseState);

        log.info("Strategy {} resumed for user {}", strategyName, userId);
    }

    public boolean isExitAllPaused(UUID userId, String strategyName) {
        return pauseStateRepository.findByUserIdAndStrategyName(userId, strategyName)
            .map(ps -> "EXIT_ALL_PAUSED".equals(ps.getCurrentState()))
            .orElse(false);
    }

    public boolean survivesDurability(UUID userId, String strategyName) {
        var pauseState = pauseStateRepository.findByUserIdAndStrategyName(userId, strategyName);
        return pauseState.isPresent() && pauseState.get().isDurable();
    }

    @Transactional
    public void recordExitAllAudit(UUID userId, UUID positionId, String symbol, String reason) {
        var audit = PositionLifecycleAudit.builder()
            .userId(userId)
            .positionId(positionId)
            .symbol(symbol)
            .oldState("OPEN")
            .newState("EXIT_ALL_TRIGGERED")
            .ownerType("RISK")
            .exitSource("KILL_SWITCH")
            .reason(reason)
            .triggeredBy("EXIT_ALL_SERVICE")
            .occurredAt(LocalDateTime.now())
            .build();

        auditRepository.save(audit);

        log.debug("EXIT_ALL audit recorded for position={}", positionId);
    }
}
