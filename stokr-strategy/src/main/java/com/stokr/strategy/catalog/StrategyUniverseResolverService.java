package com.stokr.strategy.catalog;

import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.repository.StrategyRuntimeBindingRepository;
import com.stokr.strategy.repository.StrategyUniverseSymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Resolves the active symbol set for a strategy binding from the DB universe.
 * Used by CatalogDrivenScanScheduler — does NOT touch the existing scheduler.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyUniverseResolverService {

    private final StrategyRuntimeBindingRepository bindingRepository;
    private final StrategyUniverseSymbolRepository symbolRepository;

    /**
     * Returns all active strategy-universe bindings that should be scanned.
     * Only returns bindings where: runtimeEnabled=true AND strategy.enabled=true AND group.enabled=true.
     */
    @Transactional(readOnly = true)
    public List<StrategyRuntimeBinding> resolveActiveBindings() {
        return bindingRepository.findAllActiveBindings();
    }

    /**
     * Resolves the enabled symbol list for a given universe group.
     * For derivatives groups, returns broker-specific trading symbols.
     */
    @Transactional(readOnly = true)
    public List<String> resolveSymbolsForGroup(UUID groupId) {
        return symbolRepository.findEnabledTradingSymbolsByGroupId(groupId);
    }

    /**
     * Resolves full symbol entities for a group — includes instrument metadata
     * (lot_size, expiry, strike, option_type) for asset-aware execution.
     */
    @Transactional(readOnly = true)
    public List<StrategyUniverseSymbol> resolveSymbolEntitiesForGroup(UUID groupId) {
        return symbolRepository.findAllByGroupIdAndEnabledTrue(groupId);
    }

    /**
     * Counts how many symbols are active in the group — used by admin UI.
     */
    @Transactional(readOnly = true)
    public long countSymbols(UUID groupId) {
        return symbolRepository.countByGroupIdAndEnabledTrue(groupId);
    }

    /**
     * Resolves active bindings specifically for one strategy key.
     */
    @Transactional(readOnly = true)
    public List<StrategyRuntimeBinding> resolveBindingsForStrategy(String strategyKey) {
        return bindingRepository.findActiveBindingsByStrategyKey(strategyKey);
    }
}
