package com.stokr.strategy.catalog;

import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.repository.StrategyRuntimeBindingRepository;
import com.stokr.strategy.repository.StrategyUniverseSymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the active symbol set for a strategy binding from the DB universe.
 * Used by CatalogDrivenScanScheduler — does NOT touch the existing scheduler.
 *
 * Both binding resolution and symbol set lookup are cached in-memory (default 5 min TTL)
 * so the catalog scan scheduler does NOT hit the DB on every 60-second cycle.
 * Call {@link #invalidateCache()} to force a refresh (e.g. after admin catalog updates).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyUniverseResolverService {

    private final StrategyRuntimeBindingRepository bindingRepository;
    private final StrategyUniverseSymbolRepository symbolRepository;

    @Value("${stokr.catalog.universe-cache-seconds:300}")
    private int universeCacheSeconds;

    // ── In-memory caches ──────────────────────────────────────────────────────

    private volatile List<StrategyRuntimeBinding> bindingsCache = null;
    private volatile Instant bindingsCachedAt = Instant.EPOCH;

    private final Map<UUID, List<StrategyUniverseSymbol>> symbolsCache  = new ConcurrentHashMap<>();
    private final Map<UUID, Instant>                      symbolsCachedAt = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns all active bindings. Cached for {@code stokr.catalog.universe-cache-seconds} (default 5 min).
     * During a 60s scan cycle this is called once and uses zero DB connections.
     */
    @Transactional(readOnly = true)
    public List<StrategyRuntimeBinding> resolveActiveBindings() {
        if (isFresh(bindingsCachedAt)) {
            return bindingsCache;
        }
        List<StrategyRuntimeBinding> fresh = bindingRepository.findAllActiveBindings();
        bindingsCache   = fresh;
        bindingsCachedAt = Instant.now();
        log.debug("catalog.resolver.bindings_refreshed count={}", fresh.size());
        return fresh;
    }

    /**
     * Resolves full symbol entities for a group — cached per groupId.
     * During a single scan cycle, each group is fetched from DB at most once per TTL window.
     */
    @Transactional(readOnly = true)
    public List<StrategyUniverseSymbol> resolveSymbolEntitiesForGroup(UUID groupId) {
        if (isFresh(symbolsCachedAt.getOrDefault(groupId, Instant.EPOCH))) {
            return symbolsCache.getOrDefault(groupId, List.of());
        }
        List<StrategyUniverseSymbol> fresh = symbolRepository.findAllByGroupIdAndEnabledTrue(groupId);
        symbolsCache.put(groupId, fresh);
        symbolsCachedAt.put(groupId, Instant.now());
        log.debug("catalog.resolver.symbols_refreshed groupId={} count={}", groupId, fresh.size());
        return fresh;
    }

    /** Resolves the enabled trading symbol strings for a group (light variant). */
    @Transactional(readOnly = true)
    public List<String> resolveSymbolsForGroup(UUID groupId) {
        return resolveSymbolEntitiesForGroup(groupId).stream()
                .map(s -> s.getTradingSymbol() != null ? s.getTradingSymbol() : s.getSymbol())
                .toList();
    }

    /** Counts active symbols in a group — used by admin UI (not cached, low frequency). */
    @Transactional(readOnly = true)
    public long countSymbols(UUID groupId) {
        return symbolRepository.countByGroupIdAndEnabledTrue(groupId);
    }

    /** Returns active bindings for a single strategy key — not cached (low frequency admin path). */
    @Transactional(readOnly = true)
    public List<StrategyRuntimeBinding> resolveBindingsForStrategy(String strategyKey) {
        return bindingRepository.findActiveBindingsByStrategyKey(strategyKey);
    }

    /** Force-flush the binding and symbol caches (call after catalog admin changes). */
    public void invalidateCache() {
        bindingsCache    = null;
        bindingsCachedAt = Instant.EPOCH;
        symbolsCache.clear();
        symbolsCachedAt.clear();
        log.info("catalog.resolver.cache_invalidated");
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private boolean isFresh(Instant cachedAt) {
        return cachedAt != null
                && Duration.between(cachedAt, Instant.now()).getSeconds() < universeCacheSeconds;
    }
}
