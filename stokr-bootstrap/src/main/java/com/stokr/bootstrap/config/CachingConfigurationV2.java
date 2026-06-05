package com.stokr.bootstrap.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Optimized caching configuration for Release_v2 (100-trader system)
 *
 * Strategy:
 * - User profiles: 30 min TTL (changes infrequently)
 * - Strategy configs: 30 min TTL (changes when admin updates)
 * - Portfolio exposure: 5 min TTL (updates frequently during trading)
 * - Position summary: 10 min TTL (updated when positions change)
 * - Broker status: 5 min TTL (periodic connectivity checks)
 * - Signal confidence: 2 min TTL (dynamic scoring)
 * - Market data: 1 min TTL (real-time data)
 * - Execution status: 2 min TTL (order state tracking)
 *
 * Expected cache hit rates:
 * - User profiles: 95%+ (same user trading frequently)
 * - Strategy config: 90%+ (shared across users)
 * - Position summary: 85%+ (user checks portfolio)
 * - Broker status: 80%+ (periodic checks)
 *
 * @since Release_v2 Phase 1
 */
@Configuration
@EnableCaching
public class CachingConfigurationV2 {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        Map<String, RedisCacheConfiguration> configs = new HashMap<>();

        // User profiles - 30 min (stable data)
        configs.put("user_profile",
            RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(30)));

        // User sessions - 120 min (longer-lived)
        configs.put("user_session",
            RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(120)));

        // Strategy configurations - 30 min
        configs.put("strategy_config",
            RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(30)));

        // Universe symbols - 30 min (admin-configured)
        configs.put("universe_symbols",
            RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(30)));

        // Execution configurations - 30 min
        configs.put("execution_config",
            RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(30)));

        // Risk limits - 60 min (compliance-controlled)
        configs.put("risk_limits",
            RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(60)));

        // Market data - 1 min (real-time, expires quickly)
        configs.put("market_data",
            RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(1)));

        // Broker account info - 5 min
        configs.put("broker_account",
            RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(5)));

        // Portfolio exposure - 5 min (frequently updated during trading)
        configs.put("portfolio_exposure",
            RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(5)));

        // Position summary - 10 min
        configs.put("position_summary",
            RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(10)));

        // Broker connectivity status - 5 min
        configs.put("broker_status",
            RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(5)));

        // Signal confidence scores - 2 min (dynamic)
        configs.put("signal_confidence",
            RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(2)));

        // Execution status - 2 min (order states change frequently)
        configs.put("execution_status",
            RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(2)));

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30)))
            .withInitialCacheConfigurations(configs)
            .build();
    }
}
