package com.stokr.strategy.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class CommoditiesE2eTestTriggerService {

    private static final String KEY_PREFIX = "stokr:commodities_e2e_test:fire:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    public void queueFire(String symbol) {
        String key = redisKey(symbol);
        redis.opsForValue().set(key, "1", TTL);
    }

    public boolean consumePendingFire(String symbol) {
        String key = redisKey(symbol);
        Boolean deleted = redis.delete(key);
        return Boolean.TRUE.equals(deleted);
    }

    public boolean isPending(String symbol) {
        return Boolean.TRUE.equals(redis.hasKey(redisKey(symbol)));
    }

    private static String redisKey(String symbol) {
        String bare = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (bare.contains(":")) {
            bare = bare.substring(bare.indexOf(':') + 1);
        }
        return KEY_PREFIX + bare;
    }
}
