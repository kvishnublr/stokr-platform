package com.stokr.bootstrap.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Release_v2 Phase 1: Cache Fallback Service
 *
 * Implements cache-aside pattern with automatic fallback to database
 * if Redis is unavailable.
 *
 * Benefits:
 * - Seamless degradation if Redis fails
 * - Automatic cache population on recovery
 * - No application errors even if cache down
 * - Transparent to callers
 *
 * Usage:
 * ```java
 * UserProfile profile = cacheFallback.getWithFallback(
 *     "user_profile",
 *     userId.toString(),
 *     () -> userRepository.findById(userId).orElse(null),
 *     5 * 60 * 1000  // 5 min TTL
 * );
 * ```
 *
 * @since Release_v2 Phase 1
 */
@Slf4j
@Service
@Profile("v2")
@RequiredArgsConstructor
public class CacheFallbackService {

    private final CacheManager cacheManager;

    /**
     * Get value from cache with automatic fallback to supplier if not found or cache down
     *
     * Implements cache-aside pattern:
     * 1. Try to get from cache
     * 2. If miss or cache error → call supplier
     * 3. Cache the result if cache is available
     * 4. Return value
     *
     * @param cacheName Name of the cache (e.g., "user_profile")
     * @param key Cache key
     * @param supplier Function to call if cache miss (typically DB query)
     * @return Value from cache or supplier
     */
    public <T> T getWithFallback(String cacheName, String key, Supplier<T> supplier) {
        try {
            // Try to get from cache
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                Cache.ValueWrapper wrapper = cache.get(key);
                if (wrapper != null) {
                    log.debug("Cache HIT: {} -> {}", cacheName, key);
                    return (T) wrapper.get();
                }
            }

            log.debug("Cache MISS: {} -> {}", cacheName, key);

            // Cache miss or cache unavailable → call supplier
            T value = supplier.get();

            // Try to cache the result
            try {
                if (cache != null && value != null) {
                    cache.put(key, value);
                    log.debug("Cached result: {} -> {}", cacheName, key);
                }
            } catch (Exception e) {
                log.warn("Could not cache value (cache may be down): {}", e.getMessage());
                // Ignore caching failure - still return the value from supplier
            }

            return value;
        } catch (Exception e) {
            log.error("Error in cache-aside pattern for {}-{}, falling back to supplier", cacheName, key, e);
            // Last resort: just call supplier even if everything is broken
            return supplier.get();
        }
    }

    /**
     * Get value from cache or return default if not found
     *
     * @param cacheName Name of the cache
     * @param key Cache key
     * @param defaultValue Default value if not in cache
     * @return Cached value or default
     */
    public <T> T getOrDefault(String cacheName, String key, T defaultValue) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                Cache.ValueWrapper wrapper = cache.get(key);
                if (wrapper != null) {
                    return (T) wrapper.get();
                }
            }
        } catch (Exception e) {
            log.warn("Error reading from cache {}-{}: {}", cacheName, key, e.getMessage());
        }
        return defaultValue;
    }

    /**
     * Invalidate cache entry with error handling
     *
     * @param cacheName Name of the cache
     * @param key Cache key to invalidate
     */
    public void invalidate(String cacheName, String key) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.evict(key);
                log.debug("Invalidated cache: {} -> {}", cacheName, key);
            }
        } catch (Exception e) {
            log.warn("Error invalidating cache {}-{}: {}", cacheName, key, e.getMessage());
            // Continue even if invalidation fails
        }
    }

    /**
     * Check if cache is available
     */
    public boolean isCacheAvailable(String cacheName) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                return false;
            }

            // Try a simple operation to verify cache is working
            cache.put("_health_check_" + System.nanoTime(), "ok");
            return true;
        } catch (Exception e) {
            log.warn("Cache {} is not available: {}", cacheName, e.getMessage());
            return false;
        }
    }

    /**
     * Get cache statistics
     */
    public CacheStats getStats(String cacheName) {
        CacheStats stats = new CacheStats();
        stats.cacheName = cacheName;
        stats.available = isCacheAvailable(cacheName);

        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                stats.nativeCache = cache.getNativeCache().getClass().getSimpleName();
            }
        } catch (Exception e) {
            stats.error = e.getMessage();
        }

        return stats;
    }

    /**
     * Cache statistics DTO
     */
    public static class CacheStats {
        public String cacheName;
        public boolean available;
        public String nativeCache;
        public String error;
    }
}
