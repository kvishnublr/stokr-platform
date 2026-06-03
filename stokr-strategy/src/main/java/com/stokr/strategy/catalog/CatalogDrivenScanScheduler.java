package com.stokr.strategy.catalog;

import com.stokr.common.market.LiveMarketPathOperationalGate;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.operational.StrategyExecutionMode;
import com.stokr.strategy.operational.StrategyExecutionModeService;
import com.stokr.strategy.operational.StrategyRuntimeHealthService;
import com.stokr.strategy.operational.TradingSafeStartupGateService;
import com.stokr.strategy.pipeline.SignalPipelineAuditService;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.runtime.BindingScanThrottleService;
import com.stokr.strategy.runtime.SignalCooldownService;
import com.stokr.strategy.runtime.StrategyRegistry;
import com.stokr.strategy.service.ConfidenceEngineV2;
import com.stokr.strategy.service.StrategyDailySignalCapService;
import com.stokr.strategy.service.StrategySignalEntityMapper;
import com.stokr.strategy.signals.SignalOwnerType;
import com.stokr.strategy.signals.StrategySignal;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.integrity.StrategyGeneratorIntegrityGate;
import com.stokr.strategy.lifecycle.StrategySessionEntryGuardService;
import com.stokr.marketdata.monitor.FeedHealthMonitorService;
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
 * Catalog-driven scan scheduler with execution-mode controls and runtime health telemetry.
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
    private final ConfidenceEngineV2 confidenceEngineV2;
    private final StrategyGeneratorIntegrityGate integrityGate;
    private final StrategySessionEntryGuardService sessionEntryGuard;
    private final StrategyExecutionModeService executionModeService;
    private final StrategyRuntimeHealthService runtimeHealthService;
    private final TradingSafeStartupGateService safeStartupGateService;
    private final FeedHealthMonitorService feedHealthMonitorService;
    private final ObjectProvider<LiveMarketPathOperationalGate> liveMarketPathOperationalGate;
    private final SignalPipelineAuditService signalPipelineAuditService;

    @Value("${stokr.catalog.scan.require-operational-path:false}")
    private boolean requireOperationalLivePath;

    @Value("${stokr.strategy.system-user-id:33333333-3333-3333-3333-333333333333}")
    private UUID systemUserId;

    @Scheduled(fixedDelayString = "${stokr.catalog.scan.poll-ms:60000}")
    public void scan() {
        Instant tick = Instant.now();

        if (!safeStartupGateService.isTradingReady(tick)) {
            log.warn("catalog.scan.blocked_safe_startup reason={}",
                    safeStartupGateService.snapshot(tick).get("blockReason"));
            return;
        }

        LiveMarketPathOperationalGate gate = liveMarketPathOperationalGate.getIfAvailable();
        if (requireOperationalLivePath && gate != null) {
            var assessment = gate.assess(tick);
            if (!assessment.operational()) {
                log.debug("catalog.scan.skipped_live_path_not_operational reason={}", assessment.reason());
                return;
            }
        }

        boolean feedHealthy = feedHealthMonitorService.isHealthyForLiveExecution(tick);
        if (!feedHealthy) {
            FeedHealthMonitorService.FeedHealthSnapshot feed = feedHealthMonitorService.snapshot(tick);
            log.warn("catalog.scan.feed_unhealthy equityStale={} indexStale={} level={}",
                    feed.equityStale(), feed.indexStale(), feed.level());
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
            StrategyExecutionMode mode = executionModeService.modeFor(strategyKey);

            runtimeHealthService.recordScanAttempt(strategyKey, tick);

            if (mode.skipsScheduler()) {
                log.debug("catalog.scan.disabled strategyKey={}", strategyKey);
                continue;
            }

            if (!feedHealthy) {
                runtimeHealthService.recordScanBlockedFeed(strategyKey, "FEED_STALE", tick);
                signalPipelineAuditService.recordRejection(
                        strategyKey, "*", "FEED_CHECK", "BLOCKED",
                        "FEED_STALE", "Market feed unhealthy — scan skipped for binding");
                continue;
            }

            if (dailySignalCapService.isOverCap(strategyKey, tick)) {
                log.debug("catalog.scan.daily_cap strategyKey={}", strategyKey);
                continue;
            }

            TradingStrategy strategy = strategyRegistry.get(strategyKey);
            if (strategy == null) {
                log.debug("catalog.scan.strategy_not_registered key={}", strategyKey);
                totalSkipped++;
                continue;
            }

            if (!integrityGate.isStrategyScanAllowed(strategyKey, tick)) {
                runtimeHealthService.recordScanBlockedIntegrity(strategyKey, "NIFTY_OPENING_INCOMPLETE", tick);
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
            int bindingEvaluated = 0;
            int bindingIntegrityBlocked = 0;
            int bindingHold = 0;
            for (StrategyUniverseSymbol sym : symbols) {
                try {
                    String symbol = sym.getTradingSymbol() != null ? sym.getTradingSymbol() : sym.getSymbol();
                    if (!sessionEntryGuard.isSessionEntryAllowed(strategyKey, symbol, tick)) {
                        continue;
                    }
                    if (!integrityGate.passPreEvaluate(strategyKey, symbol, tick)) {
                        bindingIntegrityBlocked++;
                        totalSymbols++;
                        continue;
                    }
                    StrategyContext ctx = buildContext(sym, symbol);
                    bindingEvaluated++;
                    StrategySignal signal = strategy.evaluate(ctx);

                    if (signal != null && signal.type() != SignalType.HOLD) {
                        runtimeHealthService.recordSignalGenerated(strategyKey, tick);

                        if (mode.skipsSignalPersist()) {
                            log.info("catalog.scan.dry_run_signal strategyKey={} symbol={} type={}",
                                    strategyKey, symbol, signal.type());
                            bindingSignals++;
                            totalSymbols++;
                            continue;
                        }

                        if (!signalCooldownService.shouldEmitSignal(symbol, strategyKey, tick)) {
                            int remaining = signalCooldownService.cooldownRemainingSeconds(symbol, strategyKey, tick);
                            signalPipelineAuditService.record(
                                    null, strategyKey, symbol, null,
                                    "COOLDOWN_BLOCKED", "COOLDOWN",
                                    "COOLDOWN", "Strategy cooldown — " + remaining + "s remaining",
                                    mode.name(), mode.name(),
                                    null, "SKIPPED", "PASSED", remaining);
                            bindingHold++;
                            totalSymbols++;
                            continue;
                        }
                        bindingSignals++;
                        persistSignal(signal, strategyKey, symbol, tick, mode);
                        log.info("catalog.scan.signal strategyKey={} symbol={} type={} mode={}",
                                strategyKey, symbol, signal.type(), mode.name());
                    } else {
                        bindingHold++;
                    }
                    totalSymbols++;
                } catch (Exception ex) {
                    log.warn("catalog.scan.eval_failed strategyKey={} symbol={}",
                            strategyKey, sym.getSymbol(), ex);
                }
            }
            totalSignals += bindingSignals;
            log.info("catalog.scan.binding_done strategyKey={} groupKey={} symbols={} evaluated={} integrityBlocked={} hold={} signals={} mode={}",
                    strategyKey, binding.getUniverseGroup().getGroupKey(), symbols.size(),
                    bindingEvaluated, bindingIntegrityBlocked, bindingHold, bindingSignals, mode.name());
        }

        log.info("catalog.scan.cycle_done bindings={} symbols={} signals={} skipped={}",
                activeBindings.size(), totalSymbols, totalSignals, totalSkipped);
    }

    private void persistSignal(
            StrategySignal signal,
            String strategyKey,
            String symbol,
            Instant candleTime,
            StrategyExecutionMode mode) {
        try {
            String pipelineMode = mode.name();
            StrategySignal scoredSignal = confidenceEngineV2.enrich(signal, strategyKey, symbol, candleTime);
            StrategySignalEntity entity = StrategySignalEntityMapper.baseEntity(
                    scoredSignal,
                    strategyKey,
                    symbol,
                    candleTime,
                    systemUserId,
                    pipelineMode,
                    "2.0.0"
            );
            StrategySignalEntityMapper.applyStreamMetadata(entity, SignalOwnerType.SYSTEM, "PENDING");
            StrategySignalEntity saved = signalPipelineService.persistAndDispatch(
                    entity, UUID.randomUUID().toString(), pipelineMode, mode.skipsBrokerExecution());
            if (saved != null) {
                signalCooldownService.recordEmitted(symbol, strategyKey, candleTime);
                runtimeHealthService.recordTradeOpened(strategyKey, candleTime);
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
