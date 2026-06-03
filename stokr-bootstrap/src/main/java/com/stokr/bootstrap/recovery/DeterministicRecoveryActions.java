package com.stokr.bootstrap.recovery;

import com.stokr.execution.safety.TradingKillSwitchService;
import com.stokr.strategy.runtime.SignalPipelineActivationService;
import com.stokr.user.broker.PlatformMarketFeedService;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeterministicRecoveryActions {

    private final PlatformRecoveryProperties properties;
    private final PlatformMarketFeedService platformMarketFeedService;
    private final SignalPipelineActivationService signalPipelineActivationService;
    private final TradingKillSwitchService tradingKillSwitchService;
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplate;
    private final ObjectProvider<DataSource> dataSource;
    private final EntityManager entityManager;

    public Map<String, Object> execute(RecoveryActionType action, OperationalRecoveryContext ctx) {
        return switch (action) {
            case REFRESH_BROKER_TOKENS -> refreshBrokerTokens();
            case RECONNECT_BROKER_WS -> reconnectBrokerWs();
            case DEACTIVATE_KILL_SWITCH -> deactivateKillSwitch();
            case ACTIVATE_SIGNAL_PIPELINE -> activatePipeline();
            case HEAL_DB_POOL -> healDbPool();
            case HEAL_REDIS -> healRedis();
            case REQUEST_CONTAINER_RESTART -> requestContainerRestart(ctx);
            case ESCALATE_HUMAN -> Map.of("escalated", true, "detail", "Human intervention required");
            case NONE -> Map.of("skipped", true);
        };
    }

    private Map<String, Object> refreshBrokerTokens() {
        Map<String, Object> summary = platformMarketFeedService.refreshAllZerodhaTokens(Duration.ofMinutes(30));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", RecoveryActionType.REFRESH_BROKER_TOKENS.name());
        out.put("vendor", properties.getBrokerVendor());
        out.put("tokenRefresh", summary);
        log.info("platform.recovery.action token_refresh vendor={} summary={}", properties.getBrokerVendor(), summary);
        return out;
    }

    private Map<String, Object> reconnectBrokerWs() {
        Map<String, Object> result = platformMarketFeedService.requestWebsocketReconnect(
                properties.getBrokerVendor(),
                "ranked_recovery_orchestrator");
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("action", RecoveryActionType.RECONNECT_BROKER_WS.name());
        log.info("platform.recovery.action ws_reconnect vendor={} result={}", properties.getBrokerVendor(), result);
        return out;
    }

    private Map<String, Object> deactivateKillSwitch() {
        Map<String, Object> status = tradingKillSwitchService.deactivate(
                TradingKillSwitchService.TriggerSource.ADMIN_API,
                "platform ranked recovery auto-disarm",
                "recovery-orchestrator");
        Map<String, Object> out = new LinkedHashMap<>(status);
        out.put("action", RecoveryActionType.DEACTIVATE_KILL_SWITCH.name());
        log.warn("platform.recovery.action kill_switch_deactivated status={}", status);
        return out;
    }

    private Map<String, Object> activatePipeline() {
        Map<String, Object> activation = signalPipelineActivationService.activate(false, false);
        Map<String, Object> out = new LinkedHashMap<>(activation);
        out.put("action", RecoveryActionType.ACTIVATE_SIGNAL_PIPELINE.name());
        log.info("platform.recovery.action pipeline_activate {}", activation);
        return out;
    }

    private Map<String, Object> healDbPool() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", RecoveryActionType.HEAL_DB_POOL.name());
        DataSource ds = dataSource.getIfAvailable();
        if (ds instanceof HikariDataSource hds) {
            try {
                hds.getHikariPoolMXBean().softEvictConnections();
                out.put("poolSoftEvict", true);
            } catch (Exception ex) {
                out.put("poolSoftEvict", false);
                out.put("poolError", ex.getMessage());
            }
        }
        try {
            Object probe = entityManager.createNativeQuery("select 1").getSingleResult();
            out.put("probeOk", probe != null);
        } catch (Exception ex) {
            out.put("probeOk", false);
            out.put("probeError", ex.getMessage());
        }
        log.warn("platform.recovery.action db_heal result={}", out);
        return out;
    }

    private Map<String, Object> healRedis() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", RecoveryActionType.HEAL_REDIS.name());
        StringRedisTemplate redis = stringRedisTemplate.getIfAvailable();
        if (redis == null) {
            out.put("status", "UNAVAILABLE");
            return out;
        }
        try (RedisConnection c = redis.getConnectionFactory().getConnection()) {
            String pong = c.ping();
            out.put("status", "CONNECTED");
            out.put("pong", pong);
        } catch (Exception ex) {
            out.put("status", "DISCONNECTED");
            out.put("error", ex.getMessage());
        }
        log.warn("platform.recovery.action redis_heal result={}", out);
        return out;
    }

    private Map<String, Object> requestContainerRestart(OperationalRecoveryContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", RecoveryActionType.REQUEST_CONTAINER_RESTART.name());
        out.put("requestedAt", ctx.collectedAt().toString());
        StringRedisTemplate redis = stringRedisTemplate.getIfAvailable();
        if (redis != null) {
            redis.opsForValue().set(
                    properties.getContainerRestartFlagKey(),
                    ctx.collectedAt().toString());
            out.put("restartFlagKey", properties.getContainerRestartFlagKey());
        }
        log.error("platform.recovery.action container_restart_requested serviceKey={}", properties.getServiceKey());
        return out;
    }
}
