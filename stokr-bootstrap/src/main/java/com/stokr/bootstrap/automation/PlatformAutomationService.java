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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformAutomationService {

    private final PlatformAutomationProperties properties;
    private final PlatformRecoveryProperties recoveryProperties;
    private final PlatformMarketFeedService platformMarketFeedService;
    private final RankedRecoveryOrchestrator orchestrator;
    private final OperationalRecoveryContextCollector contextCollector;
    private final OperationalFailureClassifier classifier;
    private final AdminOperationalSnapshotService snapshotService;

    private final ConcurrentHashMap<String, Map<String, Object>> lastRuns = new ConcurrentHashMap<>();

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        out.put("lastRuns", new LinkedHashMap<>(lastRuns));
        return out;
    }

    public Map<String, Object> runPreMarket() {
        Instant started = Instant.now();
        Map<String, Object> out = phaseHeader("pre-market", started);
        try {
            out.put("tokenRefresh", doTokenRefresh());
            orchestrator.runRecoveryCycle();
            out.put("recoveryCycle", "completed");
            OperationalRecoveryContext ctx = contextCollector.collect();
            out.put("broker", platformMarketFeedService.infrastructureSnapshot());
            out.put("readinessBlockers", readinessBlockers(ctx));
            out.put("oauthRequired", ctx.requiresUserOAuth());
            out.put("healthy", classifier.isHealthy(ctx));
        } catch (Exception ex) {
            out.put("error", ex.toString());
            log.warn("platform.automation pre_market_failed {}", ex.toString());
        }
        return finishRun("pre-market", out);
    }

    public Map<String, Object> runPreOpen() {
        Instant started = Instant.now();
        Map<String, Object> out = phaseHeader("pre-open", started);
        try {
            OperationalRecoveryContext ctxBefore = contextCollector.collect();
            orchestrator.runRecoveryCycle();
            out.put("recoveryCycle", "completed");
            if (!Boolean.TRUE.equals(ctxBefore.brokerFeed().get("operationalLivePath"))) {
                out.put("websocketReconnect", platformMarketFeedService.requestWebsocketReconnect(
                        recoveryProperties.getBrokerVendor(), "pre_open_automation"));
            } else {
                out.put("websocketReconnect", Map.of("skipped", true, "reason", "operational_live_path"));
            }
            OperationalRecoveryContext ctx = contextCollector.collect();
            out.put("broker", platformMarketFeedService.infrastructureSnapshot());
            out.put("readinessBlockers", readinessBlockers(ctx));
            out.put("oauthRequired", ctx.requiresUserOAuth());
            out.put("healthy", classifier.isHealthy(ctx));
        } catch (Exception ex) {
            out.put("error", ex.toString());
            log.warn("platform.automation pre_open_failed {}", ex.toString());
        }
        return finishRun("pre-open", out);
    }

    public Map<String, Object> runInSessionMaintenance() {
        Instant started = Instant.now();
        Map<String, Object> out = phaseHeader("in-session", started);
        try {
            OperationalRecoveryContext ctxBefore = contextCollector.collect();
            boolean healthyBefore = classifier.isHealthy(ctxBefore);
            out.put("tokenRefresh", doTokenRefresh());
            if (!healthyBefore) {
                orchestrator.runRecoveryCycle();
                out.put("recoveryCycle", "executed");
            } else {
                out.put("recoveryCycle", "skipped_healthy");
            }
            OperationalRecoveryContext ctx = contextCollector.collect();
            out.put("broker", platformMarketFeedService.infrastructureSnapshot());
            out.put("readinessBlockers", readinessBlockers(ctx));
            out.put("oauthRequired", ctx.requiresUserOAuth());
            out.put("healthy", classifier.isHealthy(ctx));
        } catch (Exception ex) {
            out.put("error", ex.toString());
            log.warn("platform.automation in_session_failed {}", ex.toString());
        }
        return finishRun("in-session", out);
    }

    public Map<String, Object> runHealthReport() {
        Instant started = Instant.now();
        Map<String, Object> out = phaseHeader("health-report", started);
        try {
            OperationalRecoveryContext ctx = contextCollector.collect();
            OperationsSnapshotDto snap = snapshotService.snapshot();
            boolean healthy = classifier.isHealthy(ctx);
            out.put("healthy", healthy);
            out.put("oauthRequired", ctx.requiresUserOAuth());
            out.put("readinessBlockers", readinessBlockers(ctx));
            out.put("recoveryContext", recoveryContextSummary(ctx));
            out.put("operationsSnapshot", snapshotSummary(snap));
            out.put("broker", platformMarketFeedService.infrastructureSnapshot());
        } catch (Exception ex) {
            out.put("error", ex.toString());
            log.warn("platform.automation health_report_failed {}", ex.toString());
        }
        return finishRun("health-report", out);
    }

    public Map<String, Object> runRecoveryCycle() {
        Instant started = Instant.now();
        Map<String, Object> out = phaseHeader("recovery-cycle", started);
        try {
            orchestrator.runRecoveryCycle();
            out.put("status", "completed");
            OperationalRecoveryContext ctx = contextCollector.collect();
            out.put("healthy", classifier.isHealthy(ctx));
            out.put("readinessBlockers", readinessBlockers(ctx));
        } catch (Exception ex) {
            out.put("error", ex.toString());
            log.warn("platform.automation recovery_cycle_failed {}", ex.toString());
        }
        return finishRun("recovery-cycle", out);
    }

    public Map<String, Object> refreshTokens() {
        Instant started = Instant.now();
        Map<String, Object> out = phaseHeader("refresh-tokens", started);
        try {
            out.put("result", doTokenRefresh());
            OperationalRecoveryContext ctx = contextCollector.collect();
            out.put("oauthRequired", ctx.requiresUserOAuth());
        } catch (Exception ex) {
            out.put("error", ex.toString());
            log.warn("platform.automation refresh_tokens_failed {}", ex.toString());
        }
        return finishRun("refresh-tokens", out);
    }

    private Map<String, Object> doTokenRefresh() {
        return platformMarketFeedService.refreshAllZerodhaTokens(tokenRefreshWindow());
    }

    private Duration tokenRefreshWindow() {
        return Duration.ofHours(Math.max(1, properties.getTokenRefreshBeforeHours()));
    }

    private static Map<String, Object> phaseHeader(String phase, Instant started) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase", phase);
        out.put("startedAt", started.toString());
        return out;
    }

    private Map<String, Object> finishRun(String phase, Map<String, Object> out) {
        out.put("finishedAt", Instant.now().toString());
        lastRuns.put(phase, Map.copyOf(out));
        log.info("platform.automation phase={} healthy={} oauthRequired={}",
                phase, out.get("healthy"), out.get("oauthRequired"));
        return out;
    }

    private List<String> readinessBlockers(OperationalRecoveryContext ctx) {
        List<String> blockers = new ArrayList<>();
        if (ctx.requiresUserOAuth()) {
            blockers.add("OAUTH_REQUIRED: complete Zerodha OAuth when refresh token is missing");
        }
        if (ctx.ingestionPausedByOperator()) {
            blockers.add("INGESTION_PAUSED_BY_OPERATOR");
        }
        if (!classifier.isHealthy(ctx)) {
            Set<OperationalFailureSignature> signatures = classifier.activeSignatures(ctx);
            for (OperationalFailureSignature sig : signatures) {
                blockers.add(sig.name());
            }
            for (String err : ctx.errorSignatures()) {
                if (!blockers.contains(err)) {
                    blockers.add(err);
                }
            }
        }
        return blockers;
    }

    private static Map<String, Object> recoveryContextSummary(OperationalRecoveryContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collectedAt", ctx.collectedAt().toString());
        out.put("actuatorHealthy", ctx.actuatorHealthy());
        out.put("actuatorHealth", ctx.actuatorHealth());
        out.put("brokerFeed", ctx.brokerFeed());
        out.put("feedHealth", ctx.feedHealth());
        out.put("redis", ctx.redis());
        out.put("database", ctx.database());
        out.put("killSwitchActive", ctx.killSwitchActive());
        out.put("executionPipeline", ctx.executionPipeline());
        out.put("scannerTelemetry", ctx.scannerTelemetry());
        out.put("activeScannerBindings", ctx.activeScannerBindings());
        out.put("errorSignatures", ctx.errorSignatures());
        return out;
    }

    private static Map<String, Object> snapshotSummary(OperationsSnapshotDto snap) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collectedAt", snap.collectedAt() != null ? snap.collectedAt().toString() : null);
        out.put("incidentCount", snap.incidents() != null ? snap.incidents().size() : 0);
        out.put("incidents", snap.incidents());
        out.put("platformMarketFeed", snap.platformMarketFeed());
        out.put("marketFreshness", snap.marketFreshness());
        out.put("scannerTelemetry", snap.scannerTelemetry());
        out.put("system", snap.system());
        return out;
    }
}
