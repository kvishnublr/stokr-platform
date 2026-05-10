package com.stokr.auth.security;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);

    private final StringRedisTemplate redis;

    @Value("${stokr.security.jwt.access-ttl-seconds:900}")
    private long accessTtlSeconds;

    private static String key(String jtiOrHash) {
        return "jwt:deny:" + jtiOrHash;
    }

    public void denyToken(String token, Date expiresAt) {
        long ttlSeconds = Math.max(1, expiresAt.getTime() - Instant.now().toEpochMilli()) / 1000;
        ttlSeconds = Math.min(ttlSeconds, accessTtlSeconds + 60);
        try {
            redis.opsForValue().set(key(hash(token)), "1", Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            log.warn("JWT deny skipped (Redis unavailable): {}", e.toString());
        }
    }

    /**
     * False when Redis is unreachable so requests are not blocked by blacklist infra outages.
     * Revocations may be ignored until Redis is back.
     */
    public boolean isDenied(String token) {
        try {
            Boolean has = redis.hasKey(key(hash(token)));
            return Boolean.TRUE.equals(has);
        } catch (RuntimeException e) {
            log.warn("JWT blacklist check skipped (Redis unavailable): {}", e.toString());
            return false;
        }
    }

    private String hash(String token) {
        return Integer.toHexString(token.hashCode());
    }
}
