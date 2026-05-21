package com.stokr.strategy.runtime;

import com.stokr.common.market.LiveMarketPathAssessment;
import com.stokr.common.market.LiveMarketPathOperationalGate;
import com.stokr.common.runtime.ExecutionPipelineRuntimeReadinessService;
import com.stokr.strategy.catalog.StrategyUniverseResolverService;
import com.stokr.strategy.domain.StrategyRuntimeBinding;
import com.stokr.strategy.telemetry.ScannerExecutionTelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyEvaluationScheduler {

    private final UnifiedStrategyRuntimeService unifiedStrategyRuntimeService;
    private final SymbolReadinessService symbolReadinessService;
    private final ScannerExecutionTelemetryService scannerExecutionTelemetryService;
    private final ObjectProvider<LiveMarketPathOperationalGate> liveMarketPathOperationalGate;
    private final ExecutionPipelineRuntimeReadinessService executionPipelineRuntimeReadinessService;
    private final StrategyUniverseResolverService universeResolverService;

    /**
     * When true (default), mean-reversion poll skips if {@link LiveMarketPathOperationalGate} reports non-operational
     * platform tape - avoids silent evaluation on stale rails.
     */
    @Value("${stokr.strategy.require-operational-live-path:true}")
    private boolean requireOperationalLivePath;

    @Scheduled(fixedDelayString = "${stokr.strategy.poll-ms:60000}")
    public void poll() {
        Instant tick = Instant.now();
        var pipeline = executionPipelineRuntimeReadinessService.snapshot();
        if (!pipeline.executionPipelineActive()) {
            scannerExecutionTelemetryService.recordPollSkipped(tick, pipeline.detail());
            log.warn("strategy.poll.blocked {}", pipeline.detail());
            return;
        }
        LiveMarketPathOperationalGate gate = liveMarketPathOperationalGate.getIfAvailable();
        if (requireOperationalLivePath && gate != null) {
            LiveMarketPathAssessment a = gate.assess(tick);
            if (!a.operational()) {
                scannerExecutionTelemetryService.recordPollSkipped(
                        tick,
                        a.platformTapeState() + ": " + a.reason()
                );
                log.info("strategy.poll.skipped tapeState={} reason={}", a.platformTapeState(), a.reason());
                return;
            }
        }
        long wallStart = System.nanoTime();
        List<String> symbols = resolveSymbolsToScan();
        int signals = 0;
        int failures = 0;
        for (String symbol : symbols) {
            SymbolReadinessService.Readiness readiness = symbolReadinessService.assess(symbol, Instant.now());
            if (!readiness.ready()) {
                scannerExecutionTelemetryService.recordPollSkipped(tick, readiness.reason() + " for " + symbol);
                failures++;
                log.info("strategy.poll.symbol_skipped symbol={} reason={}", symbol, readiness.reason());
                continue;
            }
            try {
                UnifiedStrategyRuntimeService.EvalStats stats = unifiedStrategyRuntimeService.evaluateSymbolAllStrategies(symbol);
                signals += Math.max(0, stats.emitted());
                failures += Math.max(0, stats.failed());
                if (stats.emitted() == 0 && stats.failed() == 0) {
                    log.info("strategy.poll.no_signal symbol={} note=all_generators_returned_null", symbol);
                } else {
                    log.info("strategy.poll.symbol_done symbol={} emitted={} failed={} errors={}",
                            symbol, stats.emitted(), stats.failed(), stats.errors());
                }
            } catch (Exception ex) {
                failures++;
                log.warn("strategy.poll.failed symbol={}", symbol, ex);
            }
        }
        log.info("strategy.poll.cycle_done symbols={} emitted={} failures={} tookMs={}",
                symbols.size(),
                signals,
                failures,
                (System.nanoTime() - wallStart) / 1_000_000.0);
        scannerExecutionTelemetryService.recordPollCycleFinished(
                Instant.now(),
                symbols.size(),
                signals,
                failures,
                System.nanoTime() - wallStart
        );
    }

    /**
     * Collects symbols to scan from active runtime bindings (universe-aware).
     * No static symbol-config fallback is allowed.
     */
    private List<String> resolveSymbolsToScan() {
        try {
            List<StrategyRuntimeBinding> bindings = universeResolverService.resolveActiveBindings();
            if (!bindings.isEmpty()) {
                Set<String> fromBindings = new LinkedHashSet<>();
                for (StrategyRuntimeBinding b : bindings) {
                    List<String> groupSymbols = universeResolverService.resolveSymbolsForGroup(
                            b.getUniverseGroup().getId());
                    fromBindings.addAll(groupSymbols);
                }
                if (!fromBindings.isEmpty()) {
                    log.debug("strategy.poll.symbols_from_bindings count={}", fromBindings.size());
                    return List.copyOf(fromBindings);
                }
            }
            log.warn("strategy.poll.no_binding_symbols bindings={} - skipping cycle", bindings.size());
            return List.of();
        } catch (Exception ex) {
            log.warn("strategy.poll.binding_symbol_resolve_failed - skipping cycle (no fallback)", ex);
            return List.of();
        }
    }
}
