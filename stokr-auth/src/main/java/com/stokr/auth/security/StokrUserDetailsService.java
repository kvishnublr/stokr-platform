package com.stokr.auth.security;

import com.stokr.auth.repository.AuthUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches loaded user details for 5 minutes to avoid a DB query on every authenticated HTTP request.
 * The JWT filter calls loadUserByUsername on each request — without this cache, every UI poll
 * (6 endpoints × every 5s) hits the DB, exhausting the HikariCP pool alongside scheduled tasks.
 */
@Service
@RequiredArgsConstructor
public class StokrUserDetailsService implements UserDetailsService {

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final AuthUserRepository userRepository;
    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String key = username.toLowerCase();
        CachedEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            return entry.details;
        }
        StokrUserDetails details = userRepository
                .findByEmailIgnoreCaseAndDeletedFalse(username)
                .map(StokrUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException(username));
        cache.put(key, new CachedEntry(details));
        return details;
    }

    /** Force-evict a user from the cache (call after role/password change). */
    public void evict(String email) {
        cache.remove(email.toLowerCase());
    }

    @Scheduled(fixedDelay = 300_000)
    public void evictExpired() {
        cache.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private static class CachedEntry {
        final StokrUserDetails details;
        final long expiresAt;

        CachedEntry(StokrUserDetails details) {
            this.details = details;
            this.expiresAt = System.currentTimeMillis() + CACHE_TTL_MS;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
