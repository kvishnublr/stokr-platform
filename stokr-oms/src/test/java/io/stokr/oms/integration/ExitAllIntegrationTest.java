package io.stokr.oms.integration;

import io.stokr.oms.domain.entity.*;
import io.stokr.oms.repository.*;
import io.stokr.oms.service.ExitAllService;
import io.stokr.oms.service.ExternalBrokerExitHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
class ExitAllIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ExitAllService exitAllService(StrategyPauseStateRepository pauseStateRepo,
                                              PositionLifecycleAuditRepository auditRepo) {
            return new ExitAllService(pauseStateRepo, auditRepo);
        }

        @Bean
        public ExternalBrokerExitHandler brokerExitHandler(ManualExitSuppressionRepository suppressionRepo,
                                                           PositionLifecycleAuditRepository auditRepo) {
            return new ExternalBrokerExitHandler(suppressionRepo, auditRepo);
        }
    }

    @Autowired
    private StrategyPauseStateRepository pauseStateRepository;

    @Autowired
    private PositionLifecycleAuditRepository auditRepository;

    @Autowired
    private ManualExitSuppressionRepository suppressionRepository;

    @Autowired
    private ExitAllService exitAllService;

    @Autowired
    private ExternalBrokerExitHandler brokerExitHandler;

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
    @DisplayName("EXIT_ALL triggers and survives restart/deployment")
    void testExitAllDurability() {
        // Given - Strategy is running
        StrategyPauseState pauseState = StrategyPauseState.builder()
            .userId(userId)
            .strategyName(strategyName)
            .currentState("RUNNING")
            .survivesRestart(true)
            .survivesDeployment(true)
            .build();
        pauseStateRepository.save(pauseState);

        // When - EXIT_ALL is triggered (e.g., circuit breaker)
        exitAllService.triggerExitAll(userId, strategyId, strategyName, "Circuit breaker triggered");

        // Then - Pause state is persisted as EXIT_ALL_PAUSED
        var savedState = pauseStateRepository.findByUserIdAndStrategyName(userId, strategyName);
        assertTrue(savedState.isPresent());
        assertEquals("EXIT_ALL_PAUSED", savedState.get().getCurrentState());
        assertTrue(savedState.get().isDurable());

        // Verify - Survival flags are set
        assertTrue(savedState.get().getSurvivesRestart());
        assertTrue(savedState.get().getSurvivesDeployment());
    }

    @Test
    @DisplayName("Manual broker exit suppresses all subsequent exits")
    void testManualExitSuppression() {
        // Given - Position is open
        UUID positionId = UUID.randomUUID();
        String symbol = "NIFTY";

        // When - User manually closes position in Zerodha app
        brokerExitHandler.handleManualBrokerExit(
            positionId, userId, symbol,
            "ZERODHA_APP",
            new BigDecimal("1"),
            new BigDecimal("18050.50")
        );

        // Then - Suppression record created
        var suppression = suppressionRepository.findByPositionId(positionId);
        assertTrue(suppression.isPresent());
        assertTrue(suppression.get().getSuppressAllExits());
        assertTrue(suppression.get().getSuppressSlExit());
        assertTrue(suppression.get().getSuppressTargetExit());

        // And - No exits should be allowed
        assertFalse(brokerExitHandler.canExecuteExit(positionId, "SL"));
        assertFalse(brokerExitHandler.canExecuteExit(positionId, "TARGET"));
        assertFalse(brokerExitHandler.canExecuteExit(positionId, "PRESSURE"));

        // And - Audit trail recorded
        var audits = auditRepository.findByPositionIdOrderByOccurredAtDesc(positionId);
        assertTrue(audits.size() > 0);
        assertEquals("MANUAL", audits.get(0).getOwnerType());
        assertEquals("MANUAL_BROKER", audits.get(0).getExitSource());
    }

    @Test
    @DisplayName("EXIT_ALL triggers multiple positions simultaneously")
    void testExitAllMultiplePositions() {
        // Given - 5 positions are open
        UUID[] positionIds = new UUID[5];
        for (int i = 0; i < 5; i++) {
            positionIds[i] = UUID.randomUUID();
        }

        // When - EXIT_ALL triggered
        exitAllService.triggerExitAll(userId, strategyId, strategyName, "Risk limit exceeded");

        // And - Audit recorded for each position
        for (UUID positionId : positionIds) {
            exitAllService.recordExitAllAudit(userId, positionId, "SYMBOL_" + positionId, "EXIT_ALL triggered");
        }

        // Then - 5 audit entries created
        var audits = auditRepository.findByUserIdOrderByOccurredAtDesc(userId);
        assertEquals(5, audits.size());
        for (var audit : audits) {
            assertEquals("KILL_SWITCH", audit.getExitSource());
        }
    }

    @Test
    @DisplayName("Resume strategy clears EXIT_ALL pause state")
    void testResumeStrategy() {
        // Given - Strategy in EXIT_ALL_PAUSED
        StrategyPauseState paused = StrategyPauseState.builder()
            .userId(userId)
            .strategyName(strategyName)
            .currentState("EXIT_ALL_PAUSED")
            .survivesRestart(true)
            .survivesDeployment(true)
            .pauseReason("Circuit breaker")
            .build();
        pauseStateRepository.save(paused);

        // When - Strategy is resumed
        exitAllService.resumeStrategy(userId, strategyName);

        // Then - State changes to RUNNING
        var resumed = pauseStateRepository.findByUserIdAndStrategyName(userId, strategyName);
        assertTrue(resumed.isPresent());
        assertEquals("RUNNING", resumed.get().getCurrentState());
    }

    @Test
    @DisplayName("Partial manual exit suppresses only that position")
    void testPartialManualExit() {
        // Given - Position with multiple lots
        UUID positionId = UUID.randomUUID();

        // When - User closes partial position (0.5 qty) in Zerodha
        brokerExitHandler.handleManualBrokerExit(
            positionId, userId, "NIFTY",
            "ZERODHA_APP",
            new BigDecimal("0.5"),
            new BigDecimal("18050")
        );

        // Then - Suppression marked as partial
        var suppression = suppressionRepository.findByPositionId(positionId);
        assertTrue(suppression.isPresent());
        assertTrue(suppression.get().getSuppressAllExits());
        assertEquals(new BigDecimal("0.5"), suppression.get().getManualExitQuantity());
    }

    @Test
    @DisplayName("Multiple strategies can be paused independently")
    void testMultipleStrategiesPauseIndependently() {
        // Given - 3 strategies
        String[] strategies = {"TREND_FOLLOWER_1", "MEAN_REVERSION_1", "RANGE_BOUND_1"};

        // When - Exit all for strategy 1 only
        exitAllService.triggerExitAll(userId, UUID.randomUUID(), strategies[0], "Reason");

        // Then - Only strategy 1 is EXIT_ALL_PAUSED
        assertEquals("EXIT_ALL_PAUSED",
            pauseStateRepository.findByUserIdAndStrategyName(userId, strategies[0])
                .map(StrategyPauseState::getCurrentState)
                .orElse("UNKNOWN"));

        // And - Strategies 2 and 3 unaffected
        assertFalse(pauseStateRepository.findByUserIdAndStrategyName(userId, strategies[1]).isPresent());
        assertFalse(pauseStateRepository.findByUserIdAndStrategyName(userId, strategies[2]).isPresent());
    }

    @Test
    @DisplayName("Audit trail captures complete position lifecycle")
    void testCompleteAuditTrail() {
        // Given - Position lifecycle: OPEN -> CLOSING -> CLOSED

        UUID positionId = UUID.randomUUID();
        String symbol = "NIFTY";

        // When - Position opened
        var openAudit = PositionLifecycleAudit.builder()
            .userId(userId)
            .positionId(positionId)
            .symbol(symbol)
            .oldState(null)
            .newState("OPEN")
            .ownerType("STRATEGY")
            .occurredAt(LocalDateTime.now())
            .build();
        auditRepository.save(openAudit);

        // And - Position manually closed
        brokerExitHandler.handleManualBrokerExit(
            positionId, userId, symbol,
            "ZERODHA_APP",
            new BigDecimal("1"),
            new BigDecimal("18050")
        );

        // Then - Audit trail shows complete lifecycle
        var audits = auditRepository.findByPositionIdOrderByOccurredAtDesc(positionId);
        assertEquals(2, audits.size());  // OPEN + CLOSED
        assertEquals("OPEN", audits.get(1).getNewState());
        assertEquals("CLOSED", audits.get(0).getNewState());
        assertEquals("MANUAL", audits.get(0).getOwnerType());
    }
}
