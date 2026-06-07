package com.stokr.bootstrap.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Optimized caching configuration for Release_v2 (100-trader system)
 *
 * @since Release_v2 Phase 1
 */
@Configuration
@EnableCaching
@Profile("v2")
public class CachingConfigurationV2 {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper.copy());

        RedisCacheConfiguration defaults = baseConfig(Duration.ofMinutes(30), valueSerializer);

        Map<String, RedisCacheConfiguration> configs = new HashMap<>();
        configs.put("user_profile", baseConfig(Duration.ofMinutes(30), valueSerializer));
        configs.put("user_session", baseConfig(Duration.ofMinutes(120), valueSerializer));
        configs.put("strategy_config", baseConfig(Duration.ofMinutes(30), valueSerializer));
        configs.put("universe_symbols", baseConfig(Duration.ofMinutes(30), valueSerializer));
        configs.put("execution_config", baseConfig(Duration.ofMinutes(30), valueSerializer));
        configs.put("risk_limits", baseConfig(Duration.ofMinutes(60), valueSerializer));
        configs.put("market_data", baseConfig(Duration.ofMinutes(1), valueSerializer));
        configs.put("broker_account", baseConfig(Duration.ofMinutes(5), valueSerializer));
        configs.put("portfolio_exposure", baseConfig(Duration.ofMinutes(5), valueSerializer));
        configs.put("position_summary", baseConfig(Duration.ofMinutes(10), valueSerializer));
        configs.put("broker_status", baseConfig(Duration.ofMinutes(5), valueSerializer));
        configs.put("signal_confidence", baseConfig(Duration.ofMinutes(2), valueSerializer));
        configs.put("execution_status", baseConfig(Duration.ofMinutes(2), valueSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(configs)
                .build();
    }

    private static RedisCacheConfiguration baseConfig(
            Duration ttl,
            GenericJackson2JsonRedisSerializer valueSerializer
    ) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer));
    }
}
