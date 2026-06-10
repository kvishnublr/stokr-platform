package com.stokr.bootstrap.automation;

import com.stokr.admin.dto.OperationsSnapshotDto;
import com.stokr.admin.service.AdminOperationalSnapshotService;
import com.stokr.bootstrap.recovery.OperationalFailureClassifier;
import com.stokr.bootstrap.recovery.OperationalFailureSignature;
import com.stokr.bootstrap.recovery.OperationalRecoveryContext;
import com.stokr.bootstrap.recovery.OperationalRecoveryContextCollector;
import com.stokr.bootstrap.recovery.PlatformRecoveryProperties;
import com.stokr.bootstrap.recovery.RankedRecoveryOrchestrator;
import com.stokr.user.broker.PlatformMarketFeedService;
import com.stokr.bootstrap.automation.AdminReadinessTelegramService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformAutomationServiceTest {

    @Mock
    private PlatformMarketFeedService platformMarketFeedService;
    @Mock
    private RankedRecoveryOrchestrator orchestrator;
    @Mock
    private OperationalRecoveryContextCollector contextCollector;
    @Mock
    private OperationalFailureClassifier classifier;
    @Mock
    private AdminOperationalSnapshotService snapshotService;
    @Mock
    private AdminReadinessTelegramService readinessTelegramService;

    private PlatformAutomationProperties properties;
    private PlatformRecoveryProperties recoveryProperties;
    private PlatformAutomationService service;

    @BeforeEach
    void setUp() {
        properties = new PlatformAutomationProperties();
        properties.setTokenRefreshBeforeHours(4);
        recoveryProperties = new PlatformRecoveryProperties();
        recoveryProperties.setBrokerVendor("ZERODHA");
        service = new PlatformAutomationService(
                properties,
                recoveryProperties,
                platformMarketFeedService,
                orchestrator,
                contextCollector,
                classifier,
                snapshotService,
                readinessTelegramService
        );
    }

    @Test
    void runPreMarketRefreshesTokensAndRunsRecovery() {
        OperationalRecoveryContext ctx = healthyContext(false);
        when(platformMarketFeedService.refreshAllZerodhaTokens(Duration.ofHours(4)))
                .thenReturn(Map.of("platformRefreshed", true));
        when(contextCollector.collect()).thenReturn(ctx);
        when(classifier.isHealthy(ctx)).thenReturn(true);
        when(platformMarketFeedService.infrastructureSnapshot()).thenReturn(Map.of("vendors", Map.of()));

        Map<String, Object> result = service.runPreMarket();

        verify(orchestrator).runRecoveryCycle();
        verify(platformMarketFeedService).refreshAllZerodhaTokens(Duration.ofHours(4));
        assertEquals("pre-market", result.get("phase"));
        assertEquals(false, result.get("oauthRequired"));
        assertTrue((Boolean) result.get("healthy"));
    }

    @Test
    void runPreOpenReconnectsWhenLivePathDown() {
        OperationalRecoveryContext ctxBefore = contextWithLivePath(false);
        OperationalRecoveryContext ctxAfter = healthyContext(false);
        when(contextCollector.collect()).thenReturn(ctxBefore, ctxAfter);
        when(classifier.isHealthy(ctxAfter)).thenReturn(true);
        when(platformMarketFeedService.requestWebsocketReconnect("ZERODHA", "pre_open_automation"))
                .thenReturn(Map.of("requested", true));
        when(platformMarketFeedService.infrastructureSnapshot()).thenReturn(Map.of());

        Map<String, Object> result = service.runPreOpen();

        verify(orchestrator).runRecoveryCycle();
        verify(platformMarketFeedService).requestWebsocketReconnect("ZERODHA", "pre_open_automation");
        assertEquals("pre-open", result.get("phase"));
    }

    @Test
    void runPreOpenSkipsReconnectWhenLivePathUp() {
        OperationalRecoveryContext ctx = contextWithLivePath(true);
        when(contextCollector.collect()).thenReturn(ctx);
        when(classifier.isHealthy(ctx)).thenReturn(true);
        when(platformMarketFeedService.infrastructureSnapshot()).thenReturn(Map.of());

        service.runPreOpen();

        verify(platformMarketFeedService, never()).requestWebsocketReconnect(any(), any());
    }

    @Test
    void runInSessionSkipsRecoveryWhenHealthy() {
        OperationalRecoveryContext ctx = healthyContext(false);
        when(contextCollector.collect()).thenReturn(ctx);
        when(classifier.isHealthy(ctx)).thenReturn(true);
        when(platformMarketFeedService.refreshAllZerodhaTokens(Duration.ofHours(4)))
                .thenReturn(Map.of("platformRefreshed", true));
        when(platformMarketFeedService.infrastructureSnapshot()).thenReturn(Map.of());

        Map<String, Object> result = service.runInSessionMaintenance();

        verify(orchestrator, never()).runRecoveryCycle();
        assertEquals("skipped_healthy", result.get("recoveryCycle"));
    }

    @Test
    void runInSessionRunsRecoveryWhenUnhealthy() {
        OperationalRecoveryContext ctx = healthyContext(false);
        when(contextCollector.collect()).thenReturn(ctx);
        when(classifier.isHealthy(ctx)).thenReturn(false);
        when(platformMarketFeedService.refreshAllZerodhaTokens(Duration.ofHours(4)))
                .thenReturn(Map.of("platformRefreshed", true));
        when(platformMarketFeedService.infrastructureSnapshot()).thenReturn(Map.of());

        Map<String, Object> result = service.runInSessionMaintenance();

        verify(orchestrator).runRecoveryCycle();
        assertEquals("executed", result.get("recoveryCycle"));
    }

    @Test
    void runHealthReportFlagsOAuthBlocker() {
        OperationalRecoveryContext ctx = healthyContext(true);
        when(contextCollector.collect()).thenReturn(ctx);
        when(classifier.isHealthy(ctx)).thenReturn(true);
        when(snapshotService.snapshot()).thenReturn(snapshot());
        when(platformMarketFeedService.infrastructureSnapshot()).thenReturn(Map.of());

        Map<String, Object> result = service.runHealthReport();

        assertEquals(true, result.get("oauthRequired"));
        @SuppressWarnings("unchecked")
        List<String> blockers = (List<String>) result.get("readinessBlockers");
        assertTrue(blockers.stream().anyMatch(b -> b.contains("OAUTH_REQUIRED")));
    }

    @Test
    void runHealthReportMarksUnhealthy() {
        OperationalRecoveryContext ctx = healthyContext(false);
        when(contextCollector.collect()).thenReturn(ctx);
        when(classifier.isHealthy(ctx)).thenReturn(false);
        when(classifier.activeSignatures(ctx)).thenReturn(Set.of(OperationalFailureSignature.BROKER_FEED_DOWN));
        when(snapshotService.snapshot()).thenReturn(snapshot());
        when(platformMarketFeedService.infrastructureSnapshot()).thenReturn(Map.of());

        Map<String, Object> result = service.runHealthReport();

        assertFalse((Boolean) result.get("healthy"));
    }

    private static OperationalRecoveryContext healthyContext(boolean requiresOAuth) {
        return new OperationalRecoveryContext(
                Instant.now(),
                true,
                Map.of("status", "UP"),
                Map.of("operationalLivePath", true),
                Map.of("level", "OK"),
                Map.of("status", "CONNECTED"),
                Map.of("status", "CONNECTED"),
                false,
                Map.of(),
                Map.of("executionPipelineActive", true),
                Map.of("lastPollCompletedAt", Instant.now().toString()),
                1,
                requiresOAuth,
                false,
                List.of(),
                List.of()
        );
    }

    private static OperationalRecoveryContext contextWithLivePath(boolean livePath) {
        return new OperationalRecoveryContext(
                Instant.now(),
                true,
                Map.of("status", "UP"),
                Map.of("operationalLivePath", livePath),
                Map.of("level", "OK"),
                Map.of("status", "CONNECTED"),
                Map.of("status", "CONNECTED"),
                false,
                Map.of(),
                Map.of("executionPipelineActive", true),
                Map.of("lastPollCompletedAt", Instant.now().toString()),
                1,
                false,
                false,
                List.of(),
                List.of()
        );
    }

    private static OperationsSnapshotDto snapshot() {
        return new OperationsSnapshotDto(
                Instant.now(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
    }
}
