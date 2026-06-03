package com.stokr.bootstrap.recovery;

import com.stokr.common.runtime.ExecutionPipelineRuntimeReadinessService;
import com.stokr.execution.safety.TradingKillSwitchService;
import com.stokr.marketdata.monitor.FeedHealthMonitorService;
import com.stokr.strategy.repository.StrategyRuntimeBindingRepository;
import com.stokr.strategy.telemetry.ScannerExecutionTelemetryService;
import com.stokr.user.broker.PlatformMarketFeedService;
import com.stokr.user.domain.PlatformBrokerFeedSession;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OperationalRecoveryContextCollector {

    private final PlatformRecoveryProperties properties;
    private final ObjectProvider<HealthEndpoint> healthEndpoint;
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplate;
    private final EntityManager entityManager;
    private final PlatformMarketFeedService platformMarketFeedService;
    private final PlatformBrokerFeedSessionRepository sessionRepository;
    private final FeedHealthMonitorService feedHealthMonitorService;
    private final TradingKillSwitchService tradingKillSwitchService;
    private final ExecutionPipelineRuntimeReadinessService executionPipelineRuntimeReadinessService;
    private final ScannerExecutionTelemetryService scannerExecutionTelemetryService;
    private final StrategyRuntimeBindingRepository bindingRepository;
    private final OperationalRecentLogBuffer logBuffer;

    public OperationalRecoveryContext collect() {
        Instant now = Instant.now();
        Map<String, Object> actuator = actuatorHealthMap();
        boolean actuatorHealthy = isActuatorHealthy(actuator);
        Map<String, Object> redis = redisPing();
        Map<String, Object> database = databasePing();
        Map<String, Object> brokerFeed = brokerFeedSnapshot();
        Map<String, Object> feedHealth = feedHealthMonitorService.snapshotMap(now);
        boolean killSwitchActive = tradingKillSwitchService.isActive();
        Map<String, Object> killSwitchDetail = tradingKillSwitchService.statusMap(0);
        var pipeline = executionPipelineRuntimeReadinessService.snapshot();
        Map<String, Object> executionPipeline = new LinkedHashMap<>();
        executionPipeline.put("pollExecutionMode", pipeline.pollExecutionMode());
        executionPipeline.put("pollModeRequiresRabbit", pipeline.pollModeRequiresRabbit());
        executionPipeline.put("rabbitListenersEnabled", pipeline.rabbitListenersEnabled());
        executionPipeline.put("executionPipelineActive", pipeline.executionPipelineActive());
        executionPipeline.put("detail", pipeline.detail());
        Map<String, Object> scannerTelemetry = scannerExecutionTelemetryService.snapshotOverlay(now);
        int activeBindings = bindingRepository.findAllActiveBindings().size();
        PlatformBrokerFeedSession session = sessionRepository
                .findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(properties.getBrokerVendor())
                .orElse(null);
        boolean requiresOAuth = requiresUserOAuth(session);
        boolean ingestionPaused = session != null && session.isIngestionPaused();
        List<String> errorSignatures = buildErrorSignatures(
                actuator, redis, database, brokerFeed, feedHealth, killSwitchActive, requiresOAuth, scannerTelemetry, activeBindings);
        List<String> recentLogs = logBuffer.recentLines();
        return new OperationalRecoveryContext(
                now,
                actuatorHealthy,
                actuator,
                brokerFeed,
                feedHealth,
                redis,
                database,
                killSwitchActive,
                killSwitchDetail,
                executionPipeline,
                scannerTelemetry,
                activeBindings,
                requiresOAuth,
                ingestionPaused,
                errorSignatures,
                recentLogs
        );
    }

    private Map<String, Object> actuatorHealthMap() {
        HealthEndpoint endpoint = healthEndpoint.getIfAvailable();
        if (endpoint == null) {
            return Map.of("status", "UNKNOWN", "reason", "HealthEndpoint unavailable");
        }
        try {
            HealthComponent health = endpoint.health();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", health.getStatus().getCode());
            if (health instanceof org.springframework.boot.actuate.health.CompositeHealth composite) {
                composite.getComponents().forEach((name, component) -> {
                    if (component != null) {
                        m.put(name, component.getStatus().getCode());
                    }
                });
            }
            return m;
        } catch (Exception ex) {
            return Map.of("status", "DOWN", "error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static boolean isActuatorHealthy(Map<String, Object> actuator) {
        String status = String.valueOf(actuator.getOrDefault("status", "UNKNOWN"));
        return Status.UP.getCode().equalsIgnoreCase(status);
    }

    private Map<String, Object> redisPing() {
        StringRedisTemplate redis = stringRedisTemplate.getIfAvailable();
        if (redis == null) {
            return Map.of("status", "UNKNOWN", "reason", "StringRedisTemplate unavailable");
        }
        long t0 = System.nanoTime();
        try (RedisConnection c = redis.getConnectionFactory().getConnection()) {
            String pong = c.ping();
            long ms = Math.max(0L, (System.nanoTime() - t0) / 1_000_000L);
            return Map.of("status", "CONNECTED", "pingMs", ms, "pong", pong != null ? pong : "");
        } catch (Exception ex) {
            return Map.of("status", "DISCONNECTED", "error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private Map<String, Object> databasePing() {
        long t0 = System.nanoTime();
        try {
            Object one = entityManager.createNativeQuery("select 1").getSingleResult();
            long ms = Math.max(0L, (System.nanoTime() - t0) / 1_000_000L);
            return Map.of("status", "CONNECTED", "pingMs", ms, "probe", one != null ? one.toString() : "");
        } catch (Exception ex) {
            return Map.of("status", "DISCONNECTED", "error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> brokerFeedSnapshot() {
        try {
            Map<String, Object> infra = platformMarketFeedService.infrastructureSnapshot();
            Map<String, Object> vendors = infra.get("vendors") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : Map.of();
            Map<String, Object> vendor = vendors.get(properties.getBrokerVendor()) instanceof Map<?, ?> z
                    ? (Map<String, Object>) z
                    : Map.of();
            Map<String, Object> out = new LinkedHashMap<>(vendor);
            out.put("vendor", properties.getBrokerVendor());
            return out;
        } catch (Exception ex) {
            return Map.of("status", "ERROR", "error", ex.toString());
        }
    }

    private static boolean requiresUserOAuth(PlatformBrokerFeedSession session) {
        if (session == null) {
            return false;
        }
        String state = session.getConnectionState() != null ? session.getConnectionState().toUpperCase() : "";
        if ("AUTH_EXPIRED".equals(state)) {
            return true;
        }
        boolean hasRefresh = session.getRefreshTokenEnc() != null && !session.getRefreshTokenEnc().isBlank();
        boolean hasAccess = session.getAccessTokenEnc() != null && !session.getAccessTokenEnc().isBlank();
        Instant exp = session.getTokenExpiresAt();
        boolean expired = exp != null && exp.isBefore(Instant.now());
        return !hasRefresh && (!hasAccess || expired);
    }

    private static List<String> buildErrorSignatures(
            Map<String, Object> actuator,
            Map<String, Object> redis,
            Map<String, Object> database,
            Map<String, Object> brokerFeed,
            Map<String, Object> feedHealth,
            boolean killSwitchActive,
            boolean requiresOAuth,
            Map<String, Object> scannerTelemetry,
            int activeBindings
    ) {
        List<String> out = new ArrayList<>();
        if (!Status.UP.getCode().equalsIgnoreCase(String.valueOf(actuator.get("status")))) {
            out.add("ACTUATOR_" + actuator.get("status"));
        }
        if ("DISCONNECTED".equals(String.valueOf(redis.get("status")))) {
            out.add("REDIS_DISCONNECTED:" + redis.get("error"));
        }
        if ("DISCONNECTED".equals(String.valueOf(database.get("status")))) {
            out.add("DB_DISCONNECTED:" + database.get("error"));
        }
        if (requiresOAuth || "AUTH_EXPIRED".equals(String.valueOf(brokerFeed.get("connectionState")))) {
            out.add("BROKER_AUTH_EXPIRED");
        }
        if (Boolean.FALSE.equals(brokerFeed.get("operationalLivePath"))) {
            out.add("BROKER_FEED_DOWN:" + brokerFeed.getOrDefault("operationalLivePathDetail", ""));
        }
        if ("ERROR".equals(String.valueOf(feedHealth.get("level")))) {
            out.add("FEED_HEALTH_ERROR");
        }
        if (killSwitchActive) {
            out.add("KILL_SWITCH_ACTIVE");
        }
        if (Boolean.TRUE.equals(scannerTelemetry.get("lastPollWasSkipped"))) {
            out.add("SCANNER_POLL_SKIPPED:" + scannerTelemetry.get("lastPollSkipReason"));
        }
        if (activeBindings > 0 && scannerTelemetry.get("lastPollCompletedAt") == null) {
            out.add("SCANNER_NEVER_POLLED");
        }
        return out;
    }
}
