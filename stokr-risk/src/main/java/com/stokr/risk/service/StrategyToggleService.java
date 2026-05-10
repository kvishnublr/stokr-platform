package com.stokr.risk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StrategyToggleService {

    private final StringRedisTemplate redis;

    @Value("${stokr.strategy.mean-reversion.enabled:true}")
    private boolean defaultEnabled;

    private static String key(String strategyKey) {
        return "stokr:strategy:" + strategyKey + ":enabled";
    }

    public boolean isEnabled(String strategyKey) {
        String v = redis.opsForValue().get(key(strategyKey));
        if (v == null) {
            return defaultEnabled;
        }
        return "1".equals(v) || Boolean.parseBoolean(v);
    }

    public void setEnabled(String strategyKey, boolean enabled) {
        redis.opsForValue().set(key(strategyKey), enabled ? "1" : "0");
    }
}
