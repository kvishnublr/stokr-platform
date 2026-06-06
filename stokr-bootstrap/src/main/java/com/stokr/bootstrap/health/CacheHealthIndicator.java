package com.stokr.bootstrap.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Release_v2 Phase 1: Cache Health Indicator
 *
 * Monitors Redis connection and cache health.
 * Provides real-time cache statistics.
 *
 * Reports:
 * - Redis connectivity (PING)
 * - Cache managers available
 * - Memory usage
 * - Connected status
 *
 * @since Release_v2 Phase 1
 */
@Slf4j
// @Component - Disabled: health indicator dependency management causes startup issues
@RequiredArgsConstructor
public class CacheHealthIndicator implements HealthIndicator {

    private final CacheManager cacheManager;
    private final RedisConnectionFactory connectionFactory;

    @Override
    public Health health() {
        try {
            // Test Redis connectivity
            boolean redisHealthy = checkRedisHealth();

            // Get cache statistics
            Map<String, Object> cacheStats = getCacheStatistics();

            if (redisHealthy) {
                cacheStats.put("redis_status", "UP");
                return Health.up()
                    .withDetails(cacheStats)
                    .build();
            } else {
                cacheStats.put("redis_status", "DEGRADED");
                return Health.status("DEGRADED")
                    .withDetails(cacheStats)
                    .withDetail("warning", "Redis connection unstable - using fallback (database)")
                    .build();
            }
        } catch (Exception e) {
            log.error("Cache health check failed", e);
            return Health.down()
                .withDetail("error", e.getMessage())
                .withDetail("fallback", "Database queries enabled - expect higher latency")
                .build();
        }
    }

    /**
     * Check Redis connectivity
     */
    private boolean checkRedisHealth() {
        try {
            var connection = connectionFactory.getConnection();
            if (connection != null) {
                try {
                    connection.ping();
                    return true;
                } finally {
                    connection.close();
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Redis health check failed", e);
            return false;
        }
    }

    /**
     * Get cache statistics
     */
    private Map<String, Object> getCacheStatistics() {
        Map<String, Object> stats = new HashMap<>();

        try {
            stats.put("cache_managers", cacheManager.getCacheNames().size());
            stats.put("cache_names", cacheManager.getCacheNames());
            stats.put("connection_factory", connectionFactory.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("Could not retrieve cache statistics", e);
            stats.put("error", "Statistics unavailable: " + e.getMessage());
        }

        return stats;
    }
}
