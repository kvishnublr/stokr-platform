package com.stokr.bootstrap.service;

import com.stokr.bootstrap.domain.entity.RedisHealthLog;
import com.stokr.bootstrap.repository.RedisHealthLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Service
@Slf4j
@Profile("v2")
@RequiredArgsConstructor
public class RedisConnectionMonitor {

    private final RedisConnectionFactory connectionFactory;
    private final RedisHealthLogRepository healthLogRepository;

    @Scheduled(fixedRateString = "${stokr.monitoring.redis-health.interval-ms:60000}")
    @Transactional
    public void monitorRedisHealth() {
        try {
            RedisHealthLog health = performHealthCheck();
            if (Boolean.FALSE.equals(health.getIsHealthy()) || Boolean.TRUE.equals(health.getHasIssues())) {
                healthLogRepository.save(health);
                log.warn("REDIS_HEALTH_WARNING: {} issues detected", health.getIssuesJson());
                triggerRecoveryIfNeeded(health);
            } else {
                log.debug("Redis health OK (state={})", health.getConnectionFactoryState());
            }
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            recordFailedHealthCheck(e);
        }
    }

    private RedisHealthLog performHealthCheck() {
        RedisHealthLog health = new RedisHealthLog();
        health.setIsHealthy(true);
        health.setHasIssues(false);

        try {
            RedisConnection connection = connectionFactory.getConnection();
            if (connection == null) {
                throw new IllegalStateException("Redis connection unavailable");
            }
            try (connection) {
                connection.ping();
                health.setConnectionFactoryState("STARTED");
                health.setConnectionActive(true);
                queryRedisStats(connection, health);
                detectIssues(health);
            }
        } catch (Exception e) {
            health.setIsHealthy(false);
            health.setHasIssues(true);
            health.setConnectionActive(false);
            health.setConnectionFactoryState("STOPPED");
            health.setIssuesJson("[\"Health check exception: " + e.getMessage() + "\"]");
            log.error("Redis health check exception", e);
        }

        return health;
    }

    private void queryRedisStats(RedisConnection connection, RedisHealthLog health) {
        health.setConnectionsReceived(0);
        health.setConnectionsRejected(0);
        health.setCurrentConnections(0);
        health.setCacheHits(0L);
        health.setCacheMisses(0L);
        health.setMissRatePercent(BigDecimal.ZERO);
        health.setOpsPerSecond(0);
        health.setAvgLatencyMs(BigDecimal.ZERO);
        health.setMemoryUsedMb(BigDecimal.ZERO);
        health.setMemoryPeakMb(BigDecimal.ZERO);

        try {
            Properties memory = connection.serverCommands().info("memory");
            if (memory != null) {
                health.setMemoryUsedMb(bytesToMb(memory.getProperty("used_memory")));
                health.setMemoryPeakMb(bytesToMb(memory.getProperty("used_memory_peak")));
            }

            Properties stats = connection.serverCommands().info("stats");
            if (stats != null) {
                health.setCacheHits(parseLong(stats.getProperty("keyspace_hits")));
                health.setCacheMisses(parseLong(stats.getProperty("keyspace_misses")));
                long hits = health.getCacheHits() != null ? health.getCacheHits() : 0L;
                long misses = health.getCacheMisses() != null ? health.getCacheMisses() : 0L;
                long total = hits + misses;
                if (total > 0) {
                    health.setMissRatePercent(
                            BigDecimal.valueOf(misses * 100.0 / total).setScale(2, RoundingMode.HALF_UP)
                    );
                }
            }
        } catch (Exception e) {
            log.debug("Could not read Redis INFO stats: {}", e.getMessage());
        }
    }

    private void detectIssues(RedisHealthLog health) {
        List<String> issues = new ArrayList<>();

        if (!Boolean.TRUE.equals(health.getConnectionActive())) {
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
            health.setIsHealthy(false);
            health.setHasIssues(true);
            health.setIssuesJson(issues.toString());
        }
    }

    private void triggerRecoveryIfNeeded(RedisHealthLog health) {
        if ("STOPPED".equals(health.getConnectionFactoryState())) {
            log.error("CRITICAL: Redis connection factory is STOPPED — manual intervention required");
            health.setAutoRecoveryAttempted(true);
            health.setRecoverySuccessful(false);
        }
    }

    private void recordFailedHealthCheck(Exception e) {
        RedisHealthLog failureLog = new RedisHealthLog();
        failureLog.setIsHealthy(false);
        failureLog.setHasIssues(true);
        failureLog.setConnectionActive(false);
        failureLog.setConnectionFactoryState("UNKNOWN");
        failureLog.setIsSynthetic(true);
        failureLog.setIssuesJson("[\"Health check exception: " + e.getMessage() + "\"]");
        healthLogRepository.save(failureLog);
    }

    private static BigDecimal bytesToMb(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return BigDecimal.valueOf(Long.parseLong(raw.trim()))
                    .divide(BigDecimal.valueOf(1024L * 1024L), 2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
