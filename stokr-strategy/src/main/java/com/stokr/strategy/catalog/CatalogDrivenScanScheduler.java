package com.stokr.strategy.catalog;

import com.stokr.common.market.LiveMarketPathOperationalGate;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.runtime.BindingScanThrottleService;
import com.stokr.strategy.runtime.SignalCooldownService;
import com.stokr.strategy.runtime.StrategyRegistry;
import com.stokr.strategy.service.StrategyDailySignalCapService;
import com.stokr.strategy.service.StrategySignalEntityMapper;
import com.stokr.strategy.signals.StrategySignal;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.integrity.StrategyGeneratorIntegrityGate;
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
    private final BindingScanThrottleService bindingScanThrottleService;
    private final SignalCooldownService signalCooldownService;
    private final StrategyDailySignalCapService dailySignalCapService;
    private final StrategyGeneratorIntegrityGate integrityGate;
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
            if (!bindingScanThrottleService.shouldScan(binding, tick)) {
                continue;
            }
            String strategyKey = binding.getStrategyCatalog().getStrategyKey();
            if (dailySignalCapService.isOverCap(strategyKey, tick)) {
                log.debug("catalog.scan.daily_cap strategyKey={}", strategyKey);
                continue;
            }
            TradingStrategy strategy = strategyRegistry.get(strategyKey);

            if (strategy == null) {
                log.debug("catalog.scan.strategy_not_registered key={} — skipping binding. " +
                          "Generate and deploy the template class first.", strategyKey);
                totalSkipped++;
                continue;
            }

            if (!integrityGate.isStrategyScanAllowed(strategyKey, tick)) {
                log.warn("catalog.scan.integrity_blocked strategyKey={}", strategyKey);
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
                        if (!signalCooldownService.shouldEmitSignal(symbol, strategyKey, tick)) {
                            continue;
                        }
                        bindingSignals++;
                        persistSignal(signal, strategyKey, symbol, tick);
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

    private void persistSignal(StrategySignal signal, String strategyKey, String symbol, Instant candleTime) {
        try {
            StrategySignalEntity entity = StrategySignalEntityMapper.baseEntity(
                    signal,
                    strategyKey,
                    symbol,
                    candleTime,
                    systemUserId,
                    executionMode,
                    "2.0.0"
            );
            StrategySignalEntity saved = signalPipelineService.persistAndDispatch(entity, UUID.randomUUID().toString(), executionMode);
            if (saved != null) {
                signalCooldownService.recordEmitted(symbol, strategyKey, candleTime);
            }
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
