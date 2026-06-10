package com.stokr.execution.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Deduplication service using Redis cache.
 * Prevents duplicate orders if RabbitMQ redelivers the same signal.
 *
 * How it works:
 * 1. When a signal arrives, check Redis for signal ID
 * 2. If signal already processed, return cached order ID
 * 3. If new signal, process it and cache the order ID
 * 4. Cache expires after 24 hours (covers normal trading day + next day)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeduplicationService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String DEDUP_KEY_PREFIX = "exec:signal:";
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    /**
     * Check if signal has already been processed.
     * @return cached order ID if signal was already processed, null if new
     */
    public String getProcessedOrderId(String signalId) {
        try {
            String key = DEDUP_KEY_PREFIX + signalId;
            String orderId = redisTemplate.opsForValue().get(key);

            if (orderId != null) {
                log.warn("⚠️  DUPLICATE SIGNAL DETECTED: {} | Cached order: {}",
                        signalId, orderId);
                return orderId;
            }

            return null; // Signal is new, not a duplicate
        } catch (Exception e) {
            log.error("Error checking deduplication: {}", e.getMessage());
            throw new RuntimeException("Deduplication check failed", e);
        }
    }

    /**
     * Cache the order ID for this signal to prevent duplicates.
     * If RabbitMQ redelivers the same signal, we return cached order instead.
     */
    public void cacheOrderForSignal(String signalId, String orderId) {
        try {
            String key = DEDUP_KEY_PREFIX + signalId;
            redisTemplate.opsForValue().set(key, orderId, CACHE_TTL);

            log.info("💾 DEDUP CACHE: Signal {} → Order {} (TTL: {})",
                    signalId, orderId, CACHE_TTL.toHours() + "h");
        } catch (Exception e) {
            log.error("Error caching order: {}", e.getMessage());
            // Don't fail if caching fails, but log warning
            log.warn("WARNING: Deduplication cache failed, risk of duplicate orders!");
        }
    }

    /**
     * Clear deduplication cache (used for testing or manual reset).
     */
    public void clearCache(String signalId) {
        try {
            String key = DEDUP_KEY_PREFIX + signalId;
            redisTemplate.delete(key);
            log.info("Cleared dedup cache for signal: {}", signalId);
        } catch (Exception e) {
            log.error("Error clearing cache: {}", e.getMessage());
        }
    }
}
