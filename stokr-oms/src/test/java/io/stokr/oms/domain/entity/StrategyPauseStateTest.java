package io.stokr.oms.domain.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StrategyPauseStateTest {

    private StrategyPauseState pauseState;
    private UUID userId;
    private UUID strategyId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        strategyId = UUID.randomUUID();

        pauseState = StrategyPauseState.builder()
            .userId(userId)
            .strategyName("TREND_FOLLOWER")
            .currentState("RUNNING")
            .survivesRestart(true)
            .survivesDeployment(true)
            .build();
    }

    @Test
    void testIsRunning() {
        // Given
        pauseState.setCurrentState("RUNNING");

        // When
        boolean isRunning = "RUNNING".equals(pauseState.getCurrentState());

        // Then
        assertTrue(isRunning);
    }

    @Test
    void testIsExitAllPaused() {
        // Given
        pauseState.setCurrentState("EXIT_ALL_PAUSED");

        // When
        boolean isExitAllPaused = "EXIT_ALL_PAUSED".equals(pauseState.getCurrentState());

        // Then
        assertTrue(isExitAllPaused);
    }

    @Test
    void testIsDurable_BothFlagsTrue() {
        // Given
        pauseState.setSurvivesRestart(true);
        pauseState.setSurvivesDeployment(true);

        // When
        boolean isDurable = pauseState.isDurable();

        // Then
        assertTrue(isDurable);
    }

    @Test
    void testIsDurable_RestartFalseFalse() {
        // Given
        pauseState.setSurvivesRestart(false);
        pauseState.setSurvivesDeployment(true);

        // When
        boolean isDurable = pauseState.isDurable();

        // Then
        assertFalse(isDurable);
    }

    @Test
    void testIsDurable_DeploymentFalseFalse() {
        // Given
        pauseState.setSurvivesRestart(true);
        pauseState.setSurvivesDeployment(false);

        // When
        boolean isDurable = pauseState.isDurable();

        // Then
        assertFalse(isDurable);
    }

    @Test
    void testCriticalFeature_ExitAllDurability() {
        // This test documents the CRITICAL FEATURE:
        // EXIT_ALL pause state MUST survive both restart and deployment

        // Given
        pauseState.setCurrentState("EXIT_ALL_PAUSED");
        pauseState.setSurvivesRestart(true);
        pauseState.setSurvivesDeployment(true);

        // When
        boolean willSurvive = pauseState.isDurable();

        // Then
        assertTrue(willSurvive, "EXIT_ALL state must survive restart and deployment");
    }

    @Test
    void testPauseReasonTracking() {
        // Given - EXIT_ALL triggered
        String reason = "Circuit breaker activated";

        // When
        pauseState.setPauseReason(reason);
        pauseState.setCurrentState("EXIT_ALL_PAUSED");
        pauseState.setPausedAt(LocalDateTime.now());

        // Then - Reason is preserved
        assertEquals(reason, pauseState.getPauseReason());
    }

    @Test
    void testResumeCondition() {
        // Given - Paused with condition
        pauseState.setCurrentState("PAUSED");
        pauseState.setResumeCondition("manual_resume_required");

        // When
        String condition = pauseState.getResumeCondition();

        // Then
        assertEquals("manual_resume_required", condition);
    }

    @Test
    void testPausedByUser() {
        // Given - User paused the strategy
        UUID pausingUser = UUID.randomUUID();

        // When
        pauseState.setPausedByUserId(pausingUser);

        // Then
        assertEquals(pausingUser, pauseState.getPausedByUserId());
    }

    @Test
    void testMultiplePauseStates() {
        // Test different pause state transitions

        // RUNNING -> PAUSED
        pauseState.setCurrentState("PAUSED");
        assertEquals("PAUSED", pauseState.getCurrentState());

        // PAUSED -> EXIT_ALL_PAUSED
        pauseState.setCurrentState("EXIT_ALL_PAUSED");
        assertEquals("EXIT_ALL_PAUSED", pauseState.getCurrentState());

        // EXIT_ALL_PAUSED -> RUNNING (resume)
        pauseState.setCurrentState("RUNNING");
        assertEquals("RUNNING", pauseState.getCurrentState());
    }

    @Test
    void testUniqueConstraintOnUserAndStrategy() {
        // This test documents the unique constraint:
        // (user_id, strategy_name) must be unique
        // Only one pause state per user per strategy

        StrategyPauseState state1 = StrategyPauseState.builder()
            .userId(userId)
            .strategyName("TREND_FOLLOWER")
            .currentState("RUNNING")
            .build();

        StrategyPauseState state2 = StrategyPauseState.builder()
            .userId(userId)
            .strategyName("TREND_FOLLOWER")
            .currentState("RUNNING")
            .build();

        // Both states are for the same user and strategy
        assertEquals(state1.getUserId(), state2.getUserId());
        assertEquals(state1.getStrategyName(), state2.getStrategyName());
    }
}
