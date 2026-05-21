package com.stokr.strategy.catalog;

import com.stokr.common.market.LiveMarketPathOperationalGate;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.runtime.StrategyRegistry;
import com.stokr.strategy.signals.StrategySignal;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.context.StrategyContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Catalog-driven scan scheduler.
 *
 * Reads all active strategy-universe bindings from the DB, resolves the symbol set
 * for each binding's universe group, then evaluates the bound strategy against every symbol.
 * Non-HOLD signals are persisted and dispatched via StrategySignalPipelineService.
 *
 * Enable via: {@code stokr.catalog.scan.enabled=true} (default true in application.yml).
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "stokr.catalog.scan.enabled", havingValue = "true", matchIfMissing = false)
public class CatalogDrivenScanScheduler {

    private final StrategyUniverseResolverService resolverService;
    private final StrategyRegistry strategyRegistry;
    private final StrategySignalPipelineService signalPipelineService;
    private final ObjectProvider<LiveMarketPathOperationalGate> liveMarketPathOperationalGate;

    // Separate from the main scanner gate — catalog strategies (e.g. MCX) manage their own session hours
    @Value("${stokr.catalog.scan.require-operational-path:false}")
    private boolean requireOperationalLivePath;

    @Value("${stokr.strategy.system-user-id:33333333-3333-3333-3333-333333333333}")
    private UUID systemUserId;

    @Value("${stokr.strategy.poll-execution-mode:PAPER}")
    private String executionMode;

    @Scheduled(fixedDelayString = "${stokr.catalog.scan.poll-ms:60000}")
    public void scan() {
        Instant tick = Instant.now();
        LiveMarketPathOperationalGate gate = liveMarketPathOperationalGate.getIfAvailable();
        if (requireOperationalLivePath && gate != null) {
            var assessment = gate.assess(tick);
            if (!assessment.operational()) {
                log.debug("catalog.scan.skipped_live_path_not_operational reason={}", assessment.reason());
                return;
            }
        }

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
                        persistSignal(signal, strategyKey, symbol);
                        log.info("catalog.scan.signal strategyKey={} symbol={} type={} reason={}",
                                strategyKey, symbol, signal.type(), signal.reason());
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

    private void persistSignal(StrategySignal signal, String strategyKey, String symbol) {
        try {
            StrategySignalEntity entity = new StrategySignalEntity();
            entity.setSignalType(signal.type());
            entity.setSymbol(symbol);
            entity.setStrategyName(strategyKey);
            entity.setStrategyVersion("1.0.0");
            entity.setReasonText(signal.reason());
            entity.setReason(signal.reason());
            entity.setSuggestedQty(signal.suggestedQty() != null ? signal.suggestedQty() : BigDecimal.ONE);
            entity.setCandleTimestamp(Instant.now());
            entity.setUserId(systemUserId);
            entity.setPipeline(executionMode);
            entity.setHitTarget(false);
            entity.setHitStoploss(false);
            signalPipelineService.persistAndDispatch(entity, UUID.randomUUID().toString(), executionMode);
        } catch (Exception ex) {
            log.warn("catalog.scan.persist_failed strategyKey={} symbol={} {}", strategyKey, symbol, ex.toString());
        }
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
