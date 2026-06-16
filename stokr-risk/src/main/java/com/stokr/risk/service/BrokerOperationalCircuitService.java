package com.stokr.risk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed global broker halt flag. When set, LIVE order flow must stop safely (see {@code BrokerDisconnectLiveHaltRule}).
 */
@Service
@RequiredArgsConstructor
public class BrokerOperationalCircuitService {

    public static final String REDIS_KEY_BROKER_HALT = "stokr:risk:broker-halt";

    private final StringRedisTemplate stringRedisTemplate;

    public boolean isGlobalBrokerHalt() {
        Boolean has = stringRedisTemplate.hasKey(REDIS_KEY_BROKER_HALT);
        return Boolean.TRUE.equals(has);
    }

    public void haltGlobally(String reason, Duration ttl) {
        Duration effective = ttl != null ? ttl : Duration.ofHours(24);
        String payload = reason != null && !reason.isBlank() ? reason : "halt";
        stringRedisTemplate.opsForValue().set(REDIS_KEY_BROKER_HALT, payload, effective);
    }

    public void clearGlobalHalt() {
        stringRedisTemplate.delete(REDIS_KEY_BROKER_HALT);
    }
}
