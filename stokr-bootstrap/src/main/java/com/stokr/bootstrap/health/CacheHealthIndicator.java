package com.stokr.bootstrap.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

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
@Component("cacheHealth")
@RequiredArgsConstructor
public class CacheHealthIndicator implements HealthIndicator {

    private final CacheManager cacheManager;
    private final JedisPool jedisPool;

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
                return Health.degraded()
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
     * Check Redis connectivity with PING
     */
    private boolean checkRedisHealth() {
        try (Jedis jedis = jedisPool.getResource()) {
            String pong = jedis.ping();
            return "PONG".equals(pong);
        } catch (Exception e) {
            log.warn("Redis health check failed", e);
            return false;
        }
    }

    /**
     * Get cache statistics from Jedis
     */
    private Map<String, Object> getCacheStatistics() {
        Map<String, Object> stats = new HashMap<>();

        try (Jedis jedis = jedisPool.getResource()) {
            // Get Redis INFO stats
            String info = jedis.info("memory");
            if (info != null && !info.isEmpty()) {
                String[] lines = info.split("\r\n");
                for (String line : lines) {
                    if (line.startsWith("used_memory_human")) {
                        stats.put("memory_used", line.split(":")[1]);
                    }
                    if (line.startsWith("maxmemory_human")) {
                        stats.put("memory_max", line.split(":")[1]);
                    }
                }
            }

            // Get connected clients
            String clientInfo = jedis.info("clients");
            if (clientInfo != null && !clientInfo.isEmpty()) {
                String[] lines = clientInfo.split("\r\n");
                for (String line : lines) {
                    if (line.startsWith("connected_clients")) {
                        stats.put("connected_clients", line.split(":")[1]);
                    }
                }
            }

            stats.put("cache_managers", cacheManager.getCacheNames().size());
            stats.put("cache_names", cacheManager.getCacheNames());
        } catch (Exception e) {
            log.warn("Could not retrieve cache statistics", e);
            stats.put("error", "Statistics unavailable: " + e.getMessage());
        }

        return stats;
    }
}
