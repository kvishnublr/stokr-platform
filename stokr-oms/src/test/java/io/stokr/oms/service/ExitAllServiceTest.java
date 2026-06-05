package io.stokr.oms.service;

import io.stokr.oms.domain.entity.PositionLifecycleAudit;
import io.stokr.oms.domain.entity.StrategyPauseState;
import io.stokr.oms.repository.PositionLifecycleAuditRepository;
import io.stokr.oms.repository.StrategyPauseStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExitAllServiceTest {

    @Mock
    private StrategyPauseStateRepository pauseStateRepository;

    @Mock
    private PositionLifecycleAuditRepository auditRepository;

    @InjectMocks
    private ExitAllService exitAllService;

    private UUID userId;
    private UUID strategyId;
    private String strategyName;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        strategyId = UUID.randomUUID();
        strategyName = "TREND_FOLLOWER_1";
    }

    @Test
    void testTriggerExitAll_CreatesNewPauseState() {
        // Given
        when(pauseStateRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.empty());
        when(pauseStateRepository.save(any(StrategyPauseState.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        exitAllService.triggerExitAll(userId, strategyId, strategyName, "Risk circuit breaker triggered");

        // Then
        verify(pauseStateRepository).save(argThat(ps ->
            "EXIT_ALL_PAUSED".equals(ps.getCurrentState()) &&
            ps.getSurvivesRestart() &&
            ps.getSurvivesDeployment() &&
            ps.getPauseReason().contains("Risk circuit breaker")
        ));
    }

    @Test
    void testTriggerExitAll_UpdatesExistingPauseState() {
        // Given
        StrategyPauseState existing = StrategyPauseState.builder()
            .userId(userId)
            .strategyName(strategyName)
            .currentState("RUNNING")
            .survivesRestart(true)
            .survivesDeployment(true)
            .build();

        when(pauseStateRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(existing));
        when(pauseStateRepository.save(any(StrategyPauseState.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        exitAllService.triggerExitAll(userId, strategyId, strategyName, "Manual EXIT_ALL");

        // Then
        verify(pauseStateRepository).save(argThat(ps ->
            "EXIT_ALL_PAUSED".equals(ps.getCurrentState())
        ));
    }

    @Test
    void testResumeStrategy_ChangesStateToRunning() {
        // Given
        StrategyPauseState paused = StrategyPauseState.builder()
            .userId(userId)
            .strategyName(strategyName)
            .currentState("EXIT_ALL_PAUSED")
            .build();

        when(pauseStateRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(paused));
        when(pauseStateRepository.save(any(StrategyPauseState.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        exitAllService.resumeStrategy(userId, strategyName);

        // Then
        verify(pauseStateRepository).save(argThat(ps -> "RUNNING".equals(ps.getCurrentState())));
    }

    @Test
    void testIsExitAllPaused_ReturnsTrueWhenPaused() {
        // Given
        StrategyPauseState paused = StrategyPauseState.builder()
            .currentState("EXIT_ALL_PAUSED")
            .build();

        when(pauseStateRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(paused));

        // When
        boolean result = exitAllService.isExitAllPaused(userId, strategyName);

        // Then
        assertTrue(result);
    }

    @Test
    void testIsExitAllPaused_ReturnsFalseWhenRunning() {
        // Given
        StrategyPauseState running = StrategyPauseState.builder()
            .currentState("RUNNING")
            .build();

        when(pauseStateRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(running));

        // When
        boolean result = exitAllService.isExitAllPaused(userId, strategyName);

        // Then
        assertFalse(result);
    }

    @Test
    void testSurvivesDurability_ReturnsTrueWhenBothFlagsSet() {
        // Given
        StrategyPauseState durable = StrategyPauseState.builder()
            .survivesRestart(true)
            .survivesDeployment(true)
            .build();

        when(pauseStateRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(durable));

        // When
        boolean result = exitAllService.survivesDurability(userId, strategyName);

        // Then
        assertTrue(result);
    }

    @Test
    void testSurvivesDurability_ReturnsFalseWhenRestartFlagMissing() {
        // Given
        StrategyPauseState notDurable = StrategyPauseState.builder()
            .survivesRestart(false)
            .survivesDeployment(true)
            .build();

        when(pauseStateRepository.findByUserIdAndStrategyName(userId, strategyName))
            .thenReturn(Optional.of(notDurable));

        // When
        boolean result = exitAllService.survivesDurability(userId, strategyName);

        // Then
        assertFalse(result);
    }

    @Test
    void testRecordExitAllAudit_CreatesAuditEntry() {
        // Given
        UUID positionId = UUID.randomUUID();
        String symbol = "NIFTY";
        when(auditRepository.save(any(PositionLifecycleAudit.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        exitAllService.recordExitAllAudit(userId, positionId, symbol, "Circuit breaker");

        // Then
        verify(auditRepository).save(argThat(audit ->
            positionId.equals(audit.getPositionId()) &&
            "EXIT_ALL_TRIGGERED".equals(audit.getNewState()) &&
            "KILL_SWITCH".equals(audit.getExitSource())
        ));
    }

    @Test
    void testExitAllPauseState_IsDurable() {
        // Given
        StrategyPauseState pauseState = StrategyPauseState.builder()
            .survivesRestart(true)
            .survivesDeployment(true)
            .build();

        // When
        boolean isDurable = pauseState.isDurable();

        // Then
        assertTrue(isDurable);
    }
}
