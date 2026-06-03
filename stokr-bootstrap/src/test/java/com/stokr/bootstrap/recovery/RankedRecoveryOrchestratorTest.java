package com.stokr.bootstrap.recovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RankedRecoveryOrchestratorTest {

    @Mock
    private OperationalRecoveryContextCollector contextCollector;
    @Mock
    private OperationalFailureClassifier classifier;
    @Mock
    private RecoveryStateStore stateStore;
    @Mock
    private DeterministicRecoveryActions recoveryActions;
    @Mock
    private RecoveryAlertPublisher alertPublisher;

    private PlatformRecoveryProperties properties;
    private RankedRecoveryOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        properties = new PlatformRecoveryProperties();
        properties.setRecheckWaitMs(1);
        properties.setRecheckWaitMaxMs(1);
        properties.setMaxAttemptsPerSignature(2);
        orchestrator = new RankedRecoveryOrchestrator(
                properties,
                contextCollector,
                classifier,
                stateStore,
                recoveryActions,
                alertPublisher
        );
    }

    @Test
    void skipsCycleWhenOAuthRequired() {
        OperationalRecoveryContext ctx = unhealthyContext(true, false);
        when(contextCollector.collect()).thenReturn(ctx);

        orchestrator.runRecoveryCycle();

        verify(recoveryActions, never()).execute(any(), any());
        verify(alertPublisher, never()).publishEscalation(any(), any(), any(), any(), any());
    }

    @Test
    void choosesFirstLadderActionForBrokerFeedDown() {
        OperationalRecoveryContext ctx = unhealthyContext(false, false);

        RecoveryActionType chosen = orchestrator.chooseNextAction(
                OperationalFailureSignature.BROKER_FEED_DOWN,
                OperationalRecoveryState.empty(),
                ctx);

        assertEquals(RecoveryActionType.REFRESH_BROKER_TOKENS, chosen);
    }

    @Test
    void advancesLadderAfterMaxAttempts() {
        OperationalRecoveryState state = new OperationalRecoveryState(
                OperationalFailureSignature.BROKER_FEED_DOWN,
                RecoveryActionType.REFRESH_BROKER_TOKENS,
                3,
                3,
                Instant.now().minusSeconds(120),
                null,
                Instant.now()
        );
        OperationalRecoveryContext ctx = unhealthyContext(false, false);

        RecoveryActionType chosen = orchestrator.chooseNextAction(
                OperationalFailureSignature.BROKER_FEED_DOWN,
                state,
                ctx);

        assertEquals(RecoveryActionType.RECONNECT_BROKER_WS, chosen);
    }

    @Test
    void marksSuccessWhenHealthy() {
        OperationalRecoveryContext ctx = healthyContext();
        when(contextCollector.collect()).thenReturn(ctx);
        when(classifier.isHealthy(ctx)).thenReturn(true);
        when(stateStore.load()).thenReturn(new OperationalRecoveryState(
                OperationalFailureSignature.BROKER_FEED_DOWN,
                RecoveryActionType.RECONNECT_BROKER_WS,
                1,
                1,
                Instant.now(),
                null,
                Instant.now()
        ));

        orchestrator.runRecoveryCycle();

        verify(stateStore).save(any());
        verify(alertPublisher).publishResolved(any(), any());
        verify(recoveryActions, never()).execute(any(), any());
    }

    private static OperationalRecoveryContext healthyContext() {
        return context(false, false, true);
    }

    private static OperationalRecoveryContext unhealthyContext(boolean requiresOAuth, boolean ingestionPaused) {
        return context(requiresOAuth, ingestionPaused, true);
    }

    private static OperationalRecoveryContext context(boolean requiresOAuth, boolean ingestionPaused, boolean actuatorUp) {
        return new OperationalRecoveryContext(
                Instant.now(),
                actuatorUp,
                Map.of("status", actuatorUp ? "UP" : "DOWN"),
                Map.of("operationalLivePath", false),
                Map.of("level", "ERROR"),
                Map.of("status", "CONNECTED"),
                Map.of("status", "CONNECTED"),
                false,
                Map.of(),
                Map.of("executionPipelineActive", true),
                Map.of("lastPollCompletedAt", Instant.now().toString()),
                1,
                requiresOAuth,
                ingestionPaused,
                List.of("BROKER_FEED_DOWN"),
                List.of("WARN test")
        );
    }
}
