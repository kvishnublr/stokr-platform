package com.stokr.strategy.runtime;

import com.stokr.strategy.catalog.StrategyUniverseResolverService;
import com.stokr.strategy.catalog.UniverseSyncService;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategyUniverseGroup;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.repository.StrategyRuntimeBindingRepository;
import com.stokr.strategy.repository.StrategyUniverseGroupRepository;
import com.stokr.strategy.repository.StrategyUniverseSymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Ensures catalog strategies, runtime bindings, universe symbols, and Redis toggles are ON
 * so {@link StrategyEvaluationScheduler} can emit signals.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalPipelineActivationService {

    private static final List<String> CORE_INTRADAY_KEYS = List.of(
            "MEAN_REVERSION_RANGE_FADE",
            "VWAP_MEAN_REVERSION",
            "OPENING_RANGE_BREAKOUT",
            "EMA_TREND_FOLLOW",
            "MOMENTUM_BREAKOUT",
            "BREAKOUT_COMMODITIES",
            "CASH_15M_BREAKOUT_TEST"
    );

    private static final List<String> WATCHLIST_SYMBOLS = List.of(
            "INFY", "SBIN", "RELIANCE", "TCS", "HDFCBANK", "ICICIBANK", "ITC", "BHARTIARTL"
    );

    private final StrategyDefinitionRepository definitionRepository;
    private final StrategyRuntimeBindingRepository bindingRepository;
    private final StrategyUniverseGroupRepository groupRepository;
    private final StrategyUniverseSymbolRepository symbolRepository;
    private final StrategyUniverseResolverService universeResolverService;
    private final List<UniverseSyncService> universeSyncServices;
    private final TransactionTemplate transactionTemplate;

    @Value("${stokr.strategy.pipeline.default-universe-group:NIFTY_50}")
    private String defaultUniverseGroupKey;

    @Value("${stokr.strategy.pipeline.fast-scan-interval-seconds:5}")
    private int fastScanIntervalSeconds;

    public Map<String, Object> activate(boolean syncUniverses, boolean runImmediatePoll) {
        int universesSynced = syncUniverses ? syncUniversesIsolated() : 0;
        Map<String, Object> out = activateCore(universesSynced);
        out.put("runImmediatePoll", runImmediatePoll);
        log.info("signal.pipeline.activated {}", out);
        return out;
    }

    /** Core enablement in one short transaction (no universe bulk sync). */
    @Transactional
    protected Map<String, Object> activateCore(int universesSynced) {
        int strategiesEnabled = 0;
        int bindingsEnabled = 0;
        int bindingsCreated = 0;
        int symbolsSeeded = 0;

        for (StrategyDefinition def : definitionRepository.findAll().stream().filter(d -> !d.isDeleted()).toList()) {
            if (!def.isEnabled()) {
                def.setEnabled(true);
                definitionRepository.save(def);
                strategiesEnabled++;
            }
        }

        Optional<StrategyUniverseGroup> defaultGroup = groupRepository.findByGroupKey(defaultUniverseGroupKey);
        if (defaultGroup.isEmpty()) {
            defaultGroup = groupRepository.findByGroupKey("CUSTOM_WATCHLIST");
        }

        StrategyUniverseGroup watchlist = groupRepository.findByGroupKey("CUSTOM_WATCHLIST").orElse(null);
        if (watchlist != null) {
            for (String sym : WATCHLIST_SYMBOLS) {
                if (ensureSymbol(watchlist, sym)) {
                    symbolsSeeded++;
                }
            }
        }

        StrategyUniverseGroup bindGroup = defaultGroup.orElse(watchlist);
        if (bindGroup != null) {
            for (String key : CORE_INTRADAY_KEYS) {
                Optional<StrategyDefinition> def = definitionRepository.findByStrategyKeyAndDeletedFalse(key);
                if (def.isEmpty()) {
                    continue;
                }
                Optional<StrategyRuntimeBinding> existing = bindingRepository
                        .findByStrategyCatalogIdAndUniverseGroupId(def.get().getId(), bindGroup.getId());
                if (existing.isPresent()) {
                    StrategyRuntimeBinding b = existing.get();
                    if (!b.isRuntimeEnabled() || b.getScanIntervalSeconds() > fastScanIntervalSeconds) {
                        b.setRuntimeEnabled(true);
                        b.setScanIntervalSeconds(fastScanIntervalSeconds);
                        bindingRepository.save(b);
                        bindingsEnabled++;
                    }
                } else {
                    StrategyRuntimeBinding b = new StrategyRuntimeBinding();
                    b.setStrategyCatalog(def.get());
                    b.setUniverseGroup(bindGroup);
                    b.setRuntimeEnabled(true);
                    b.setMaxPositions(5);
                    b.setRiskProfile("MEDIUM");
                    b.setScanIntervalSeconds(fastScanIntervalSeconds);
                    bindingRepository.save(b);
                    bindingsCreated++;
                }
            }
        }

        for (StrategyRuntimeBinding b : bindingRepository.findAll()) {
            if (!b.isRuntimeEnabled()) {
                b.setRuntimeEnabled(true);
                if (b.getScanIntervalSeconds() > fastScanIntervalSeconds) {
                    b.setScanIntervalSeconds(fastScanIntervalSeconds);
                }
                bindingRepository.save(b);
                bindingsEnabled++;
            }
        }

        universeResolverService.invalidateCache();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategiesEnabled", strategiesEnabled);
        out.put("bindingsEnabled", bindingsEnabled);
        out.put("bindingsCreated", bindingsCreated);
        out.put("symbolsSeeded", symbolsSeeded);
        out.put("universesSynced", universesSynced);
        out.put("defaultUniverseGroup", bindGroup != null ? bindGroup.getGroupKey() : null);
        out.put("activeBindings", bindingRepository.findAllActiveBindings().size());
        out.put("activatedAt", Instant.now().toString());
        return out;
    }

    /** Each universe sync in its own transaction so duplicate-key races do not abort enablement. */
    private int syncUniversesIsolated() {
        int universesSynced = 0;
        for (UniverseSyncService sync : universeSyncServices) {
            for (String groupKey : sync.supportedGroupKeys()) {
                Integer n = transactionTemplate.execute(status -> {
                    try {
                        return sync.sync(groupKey);
                    } catch (Exception ex) {
                        status.setRollbackOnly();
                        log.warn("pipeline.activate.universe_sync_failed group={} {}", groupKey, ex.toString());
                        return 0;
                    }
                });
                if (n != null && n > 0) {
                    universesSynced += n;
                }
            }
        }
        universeResolverService.invalidateCache();
        return universesSynced;
    }

    private boolean ensureSymbol(StrategyUniverseGroup group, String symbol) {
        String upper = symbol.trim().toUpperCase(Locale.ROOT);
        boolean exists = symbolRepository.findAllByGroupIdAndEnabledTrue(group.getId()).stream()
                .anyMatch(s -> upper.equalsIgnoreCase(s.getSymbol()) || upper.equalsIgnoreCase(s.getTradingSymbol()));
        if (exists) {
            return false;
        }
        try {
            StrategyUniverseSymbol row = new StrategyUniverseSymbol();
            row.setGroup(group);
            row.setSymbol(upper);
            row.setTradingSymbol(upper);
            row.setExchange("NSE");
            row.setInstrumentType("EQ");
            row.setEnabled(true);
            symbolRepository.save(row);
            return true;
        } catch (DataIntegrityViolationException ex) {
            log.debug("pipeline.activate.symbol_exists group={} symbol={}", group.getGroupKey(), upper);
            return false;
        }
    }
}
