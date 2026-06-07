package com.stokr.bootstrap.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Release_v2 Phase 2: Rate Limiter Service
 *
 * @since Release_v2 Phase 2
 */
@Slf4j
@Service
@Profile("v2")
@RequiredArgsConstructor
public class RateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${stokr.rate-limiting.enabled:true}")
    private boolean enabled;

    public enum RateLimitEndpoint {
        ORDERS(100, 60),
        SIGNALS(50, 60),
        PORTFOLIO(200, 60);

        private final int tokensPerWindow;
        private final int windowSeconds;

        RateLimitEndpoint(int tokensPerWindow, int windowSeconds) {
            this.tokensPerWindow = tokensPerWindow;
            this.windowSeconds = windowSeconds;
        }

        public int getTokensPerWindow() {
            return tokensPerWindow;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean allowRequest(UUID userId, RateLimitEndpoint endpoint) {
        if (!enabled) {
            return true;
        }

        String key = getRateLimitKey(endpoint, userId);
        RateLimitState state = getRateLimitState(key, endpoint);

        long elapsedSeconds = (System.currentTimeMillis() - state.lastRefillAt) / 1000;
        int tokensToAdd = (int) (elapsedSeconds * endpoint.getTokensPerWindow() / endpoint.getWindowSeconds());

        int newTokens = Math.min(
                state.tokensRemaining + tokensToAdd,
                endpoint.getTokensPerWindow()
        );

        if (newTokens > 0) {
            newTokens--;
            state.tokensRemaining = newTokens;
            state.lastRefillAt = System.currentTimeMillis();
            state.requestsAllowed++;
            saveRateLimitState(key, state, endpoint);
            log.debug("Rate limit ALLOWED: {} {} (tokens: {}/{})",
                    userId, endpoint, newTokens, endpoint.getTokensPerWindow());
            return true;
        }

        state.requestsQueued++;
        if (state.firstQueuedAt == 0) {
            state.firstQueuedAt = System.currentTimeMillis();
        }
        saveRateLimitState(key, state, endpoint);
        log.warn("Rate limit EXCEEDED: {} {} (queued: {})", userId, endpoint, state.requestsQueued);
        return false;
    }

    public int getTokensRemaining(UUID userId, RateLimitEndpoint endpoint) {
        String key = getRateLimitKey(endpoint, userId);
        RateLimitState state = getRateLimitState(key, endpoint);

        long elapsedSeconds = (System.currentTimeMillis() - state.lastRefillAt) / 1000;
        int tokensToAdd = (int) (elapsedSeconds * endpoint.getTokensPerWindow() / endpoint.getWindowSeconds());
        return Math.min(state.tokensRemaining + tokensToAdd, endpoint.getTokensPerWindow());
    }

    public int getQueuedRequestCount(UUID userId, RateLimitEndpoint endpoint) {
        String key = getRateLimitKey(endpoint, userId);
        RateLimitState state = getRateLimitState(key, endpoint);
        return state.requestsQueued;
    }

    public long getTimeUntilReset(UUID userId, RateLimitEndpoint endpoint) {
        String key = getRateLimitKey(endpoint, userId);
        RateLimitState state = getRateLimitState(key, endpoint);

        if (getTokensRemaining(userId, endpoint) > 0) {
            return 0;
        }

        long refillTime = (1000L * endpoint.getWindowSeconds()) / endpoint.getTokensPerWindow();
        return refillTime;
    }

    public void resetRateLimit(UUID userId, RateLimitEndpoint endpoint) {
        String key = getRateLimitKey(endpoint, userId);
        redisTemplate.delete(key);
        log.info("Rate limit reset for {} {}", userId, endpoint);
    }

    public RateLimitStats getStats(UUID userId, RateLimitEndpoint endpoint) {
        String key = getRateLimitKey(endpoint, userId);
        RateLimitState state = getRateLimitState(key, endpoint);

        RateLimitStats stats = new RateLimitStats();
        stats.userId = userId;
        stats.endpoint = endpoint.name();
        stats.tokensRemaining = getTokensRemaining(userId, endpoint);
        stats.maxTokens = endpoint.getTokensPerWindow();
        stats.requestsAllowed = state.requestsAllowed;
        stats.requestsQueued = state.requestsQueued;
        stats.timeUntilReset = getTimeUntilReset(userId, endpoint);
        return stats;
    }

    private RateLimitState getRateLimitState(String key, RateLimitEndpoint endpoint) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null || value.isBlank()) {
            return newRateLimitState(endpoint);
        }

        try {
            String[] parts = value.split(",", 5);
            if (parts.length < 5) {
                return newRateLimitState(endpoint);
            }
            RateLimitState state = new RateLimitState();
            state.tokensRemaining = Integer.parseInt(parts[0]);
            state.lastRefillAt = Long.parseLong(parts[1]);
            state.requestsAllowed = Integer.parseInt(parts[2]);
            state.requestsQueued = Integer.parseInt(parts[3]);
            state.firstQueuedAt = Long.parseLong(parts[4]);
            return state;
        } catch (Exception e) {
            log.warn("Error parsing rate limit state for key {}, resetting", key, e);
            return newRateLimitState(endpoint);
        }
    }

    private RateLimitState newRateLimitState(RateLimitEndpoint endpoint) {
        RateLimitState state = new RateLimitState();
        state.tokensRemaining = endpoint.getTokensPerWindow();
        state.lastRefillAt = System.currentTimeMillis();
        state.requestsAllowed = 0;
        state.requestsQueued = 0;
        state.firstQueuedAt = 0;
        return state;
    }

    private void saveRateLimitState(String key, RateLimitState state, RateLimitEndpoint endpoint) {
        String value = String.format("%d,%d,%d,%d,%d",
                state.tokensRemaining,
                state.lastRefillAt,
                state.requestsAllowed,
                state.requestsQueued,
                state.firstQueuedAt
        );

        redisTemplate.opsForValue().set(
                key,
                value,
                endpoint.getWindowSeconds(),
                TimeUnit.SECONDS
        );
    }

    private String getRateLimitKey(RateLimitEndpoint endpoint, UUID userId) {
        return String.format("rate_limit:%s:%s", endpoint.name(), userId);
    }

    public static class RateLimitState {
        public int tokensRemaining;
        public long lastRefillAt;
        public int requestsAllowed;
        public int requestsQueued;
        public long firstQueuedAt;
    }

    public static class RateLimitStats {
        public UUID userId;
        public String endpoint;
        public int tokensRemaining;
        public int maxTokens;
        public int requestsAllowed;
        public int requestsQueued;
        public long timeUntilReset;
    }
}
