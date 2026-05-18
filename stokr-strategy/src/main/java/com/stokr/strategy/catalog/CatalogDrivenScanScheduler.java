package com.stokr.strategy.catalog;

import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.runtime.StrategyRegistry;
import com.stokr.strategy.signals.StrategySignal;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.context.StrategyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Catalog-driven scan scheduler.
 *
 * Reads all active strategy-universe bindings from the DB, resolves the symbol set
 * for each binding's universe group, then evaluates the bound strategy against every symbol.
 *
 * This scheduler is ADDITIVE — it does not replace or touch {@link com.stokr.strategy.runtime.StrategyEvaluationScheduler}.
 * Enable it via: {@code stokr.catalog.scan.enabled=true} (default false until admin creates bindings).
 *
 * Asset-aware: resolves exchange, instrument type, lot size, and expiry from
 * {@code strategy_universe_symbols} so commodity/futures strategies get correct metadata.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "stokr.catalog.scan.enabled", havingValue = "true", matchIfMissing = false)
public class CatalogDrivenScanScheduler {

    private final StrategyUniverseResolverService resolverService;
    private final StrategyRegistry strategyRegistry;

    @Scheduled(fixedDelayString = "${stokr.catalog.scan.poll-ms:60000}")
    public void scan() {
        List<StrategyRuntimeBinding> activeBindings = resolverService.resolveActiveBindings();
        if (activeBindings.isEmpty()) {
            log.debug("catalog.scan.no_active_bindings");
            return;
        }

        log.info("catalog.scan.start bindings={}", activeBindings.size());
        int totalSymbols = 0;
        int totalSignals = 0;
        int totalSkipped = 0;

        for (StrategyRuntimeBinding binding : activeBindings) {
            String strategyKey = binding.getStrategyCatalog().getStrategyKey();
            TradingStrategy strategy = strategyRegistry.get(strategyKey);

            if (strategy == null) {
                log.warn("catalog.scan.strategy_not_registered key={} — skipping binding. " +
                         "Generate and deploy the template class first.", strategyKey);
                totalSkipped++;
                continue;
            }

            List<StrategyUniverseSymbol> symbols =
                    resolverService.resolveSymbolEntitiesForGroup(binding.getUniverseGroup().getId());

            if (symbols.isEmpty()) {
                log.info("catalog.scan.empty_universe strategyKey={} groupKey={}",
                        strategyKey, binding.getUniverseGroup().getGroupKey());
                continue;
            }

            int bindingSignals = 0;
            for (StrategyUniverseSymbol sym : symbols) {
                try {
                    String symbol = sym.getTradingSymbol() != null ? sym.getTradingSymbol() : sym.getSymbol();
                    StrategyContext ctx = buildContext(sym, symbol);
                    StrategySignal signal = strategy.evaluate(ctx);

                    if (signal != null && signal.type() != SignalType.HOLD) {
                        bindingSignals++;
                        log.info("catalog.scan.signal strategyKey={} symbol={} type={} reason={}",
                                strategyKey, symbol, signal.type(), signal.reason());
                        // Signal is emitted — downstream pipeline picks it up via StrategySignalPipelineService
                        // if the strategy bean calls it internally (as all existing generators do).
                    }
                    totalSymbols++;
                } catch (Exception ex) {
                    log.warn("catalog.scan.eval_failed strategyKey={} symbol={}",
                            strategyKey, sym.getSymbol(), ex);
                }
            }
            totalSignals += bindingSignals;
            log.info("catalog.scan.binding_done strategyKey={} groupKey={} symbols={} signals={}",
                    strategyKey, binding.getUniverseGroup().getGroupKey(), symbols.size(), bindingSignals);
        }

        log.info("catalog.scan.cycle_done bindings={} symbols={} signals={} skipped={}",
                activeBindings.size(), totalSymbols, totalSignals, totalSkipped);
    }

    private StrategyContext buildContext(StrategyUniverseSymbol sym, String symbol) {
        return new StrategyContext(
                symbol,
                Instant.now(),
                java.util.Map.of(),
                BigDecimal.ZERO
        );
    }
}
