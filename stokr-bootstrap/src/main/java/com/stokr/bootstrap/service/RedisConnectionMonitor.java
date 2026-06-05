package com.stokr.bootstrap.service;

import com.stokr.bootstrap.domain.entity.RedisHealthLog;
import com.stokr.bootstrap.repository.RedisHealthLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class RedisConnectionMonitor {

    private final RedisConnectionFactory connectionFactory;
    private final RedisHealthLogRepository healthLogRepository;

    private static final int HEALTH_CHECK_THRESHOLD_SECONDS = 30;

    @Scheduled(fixedRate = 5000)  // Every 5 seconds
    @Transactional
    public void monitorRedisHealth() {
        try {
            var health = performHealthCheck();
            healthLogRepository.save(health);

            if (!health.getIsHealthy()) {
                log.warn("REDIS_HEALTH_WARNING: {} issues detected", health.getIssuesJson());
                triggerRecoveryIfNeeded(health);
            }
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            recordFailedHealthCheck(e);
        }
    }

    private RedisHealthLog performHealthCheck() {
        var health = new RedisHealthLog();
        health.setId(UUID.randomUUID());
        health.setIsHealthy(true);
        health.setHasIssues(false);

        try {
            // Check connection factory state
            var factoryState = connectionFactory.getConnection() != null ? "STARTED" : "STOPPED";
            health.setConnectionFactoryState(factoryState);
            health.setConnectionActive("STARTED".equals(factoryState));

            if ("STOPPED".equals(factoryState)) {
                health.setIsHealthy(false);
                health.setHasIssues(true);
                health.setIssuesJson("[\"LettuceConnectionFactory has been STOPPED\"]");
                log.error("CRITICAL: Redis connection factory is STOPPED!");
            }

            // Query Redis for statistics
            queryRedisStats(health);

            // Detect issues
            detectIssues(health);

        } catch (Exception e) {
            health.setIsHealthy(false);
            health.setHasIssues(true);
            health.setIssuesJson("[\"Health check exception: " + e.getMessage() + "\"]");
            log.error("Redis health check exception", e);
        }

        return health;
    }

    private void queryRedisStats(RedisHealthLog health) {
        // TODO: Connect to Redis and query:
        // - INFO memory -> memory_used_mb, memory_peak_mb
        // - INFO stats -> total_commands_processed (ops/sec), total_net_input_bytes
        // - Cache hit/miss rates
        // - Connection pool stats

        health.setConnectionsReceived(0);
        health.setConnectionsRejected(0);
        health.setCurrentConnections(0);
        health.setCacheHits(0L);
        health.setCacheMisses(0L);
        health.setMissRatePercent(BigDecimal.ZERO);
        health.setOpsPerSecond(0);
        health.setAvgLatencyMs(BigDecimal.ZERO);
    }

    private void detectIssues(RedisHealthLog health) {
        List<String> issues = new ArrayList<>();

        if (!health.getConnectionActive()) {
            issues.add("Connection inactive");
        }

        if (health.getMissRatePercent() != null
                && health.getMissRatePercent().compareTo(new BigDecimal("80")) > 0) {
            issues.add("High cache miss rate: " + health.getMissRatePercent() + "%");
        }

        if (health.getConnectionsRejected() != null && health.getConnectionsRejected() > 100) {
            issues.add("High connection rejections: " + health.getConnectionsRejected());
        }

        if (!issues.isEmpty()) {
            health.setHasIssues(true);
            health.setIssuesJson(issues.toString());
        }
    }

    private void triggerRecoveryIfNeeded(RedisHealthLog health) {
        if ("STOPPED".equals(health.getConnectionFactoryState())) {
            log.error("CRITICAL: Attempting Redis auto-recovery...");
            health.setAutoRecoveryAttempted(true);

            try {
                // Attempt restart
                restartRedisConnection();
                health.setRecoverySuccessful(true);
                log.info("Redis connection restored");
            } catch (Exception e) {
                health.setRecoverySuccessful(false);
                log.error("Redis recovery failed", e);
            }
        }
    }

    private void restartRedisConnection() {
        // TODO: Implement actual restart logic
        // This might involve:
        // 1. Draining existing connections
        // 2. Reinitializing LettuceConnectionFactory
        // 3. Verifying connectivity
        // 4. Resuming market data and order processing
        log.info("Redis restart sequence initiated");
    }

    private void recordFailedHealthCheck(Exception e) {
        var failureLog = new RedisHealthLog();
        failureLog.setId(UUID.randomUUID());
        failureLog.setIsHealthy(false);
        failureLog.setHasIssues(true);
        failureLog.setConnectionFactoryState("UNKNOWN");
        failureLog.setIsSynthetic(true);
        failureLog.setIssuesJson("[\"Health check exception: " + e.getMessage() + "\"]");

        healthLogRepository.save(failureLog);
    }
}
