package com.stokr.strategy.runtime;

import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.runtime.ExecutionPipelineRuntimeReadinessService;
import com.stokr.strategy.cash.CashFifteenMinuteBreakoutSignalGenerator;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.ematrend.EmaTrendFollowingSignalGenerator;
import com.stokr.strategy.keys.StrategyKeys;
import com.stokr.strategy.meanreversion.MeanReversionSignalGenerator;
import com.stokr.strategy.momentum.MomentumBreakoutSignalGenerator;
import com.stokr.strategy.openingrange.OpeningRangeBreakoutSignalGenerator;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.repository.StrategyRuntimeBindingRepository;
import com.stokr.strategy.telemetry.ScannerExecutionTelemetryService;
import com.stokr.strategy.vwap.VwapMeanReversionSignalGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class UnifiedStrategyRuntimeService {

    /** Suppress repeated identical errors per symbol for 5 minutes to avoid log storms. */
    private static final long ERROR_LOG_COOLDOWN_MS = 5 * 60_000L;
    private static final long UNIVERSE_CACHE_TTL_MS = 60_000L;

    private final Map<String, Long> errorLoggedAt = new ConcurrentHashMap<>();

    /** Cache: strategyKey → set of allowed symbols (trading symbol keys). Refreshed every minute. */
    private final Map<String, Set<String>> universeCache = new ConcurrentHashMap<>();
    private volatile long universeCacheRefreshedAt = 0L;

    private final MeanReversionSignalGenerator meanReversionSignalGenerator;
    private final EmaTrendFollowingSignalGenerator emaTrendFollowingSignalGenerator;
    private final MomentumBreakoutSignalGenerator momentumBreakoutSignalGenerator;
    private final OpeningRangeBreakoutSignalGenerator openingRangeBreakoutSignalGenerator;
    private final VwapMeanReversionSignalGenerator vwapMeanReversionSignalGenerator;
    private final CashFifteenMinuteBreakoutSignalGenerator cashFifteenMinuteBreakoutSignalGenerator;
    private final StrategySignalPipelineService pipelineService;
    private final ScannerExecutionTelemetryService scannerExecutionTelemetryService;
    private final ExecutionPipelineRuntimeReadinessService executionPipelineRuntimeReadinessService;
    private final StrategyRuntimeBindingRepository runtimeBindingRepository;

    @Value("${stokr.strategy.poll-execution-mode:PAPER}")
    private String pollExecutionMode;

    @Value("${stokr.strategy.poll-timeframe:1m}")
    private String pollTimeframe;

    @Value("${stokr.strategy.system-user-id:33333333-3333-3333-3333-333333333333}")
    private UUID systemUserId;

    public EvalStats evaluateSymbolAllStrategies(String symbol) {
        long t0 = System.nanoTime();
        int emitted = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        try {
            String mode = resolvePollExecutionMode();
            if (!executionPipelineRuntimeReadinessService.canRouteExecutionMode(mode)) {
                throw new IllegalStateException("Execution pipeline disabled for " + mode);
            }
            for (StrategySignalEntity sig : evaluateCandidates(symbol)) {
                if (sig == null) {
                    continue;
                }
                try {
                    String cid = CorrelationIdHolder.get();
                    if (cid == null || cid.isBlank()) {
                        cid = UUID.randomUUID().toString();
                    }
                    StrategySignalEntity persisted = pipelineService.persistAndDispatch(sig, cid, mode);
                    if (persisted != null) {
                        emitted++;
                    }
                } catch (Exception ex) {
                    failed++;
                    errors.add(ex.getClass().getSimpleName());
                    if (shouldLog(symbol + "|dispatch|" + ex.getClass().getSimpleName())) {
                        log.error("strategy.runtime.dispatch_failed symbol={} strategy={} (suppressed for 5m after this)", symbol, sig.getStrategyName(), ex);
                    }
                }
            }
            return new EvalStats(emitted, failed, errors);
        } catch (Exception ex) {
            failed++;
            errors.add(ex.getClass().getSimpleName());
            if (shouldLog(symbol + "|eval|" + ex.getClass().getSimpleName())) {
                log.error("strategy.runtime.eval_failed symbol={} (suppressed for 5m after this)", symbol, ex);
            }
            return new EvalStats(emitted, failed, errors);
        } finally {
            scannerExecutionTelemetryService.recordEvaluationComplete(symbol, System.nanoTime() - t0, emitted > 0, null);
        }
    }

    private List<StrategySignalEntity> evaluateCandidates(String symbol) {
        Instant now = Instant.now();
        UUID uid = systemUserId;
        List<StrategySignalEntity> out = new ArrayList<>(6);
        if (isSymbolAllowed(StrategyKeys.MEAN_REVERSION_RANGE_FADE, symbol))
            out.add(meanReversionSignalGenerator.evaluatePersistable(symbol, uid, null, "LIVE"));
        if (isSymbolAllowed(StrategyKeys.VWAP_MEAN_REVERSION, symbol))
            out.add(vwapMeanReversionSignalGenerator.evaluatePersistableAtOpen(symbol, uid, null, "LIVE", now, pollTimeframe));
        if (isSymbolAllowed(StrategyKeys.OPENING_RANGE_BREAKOUT, symbol))
            out.add(openingRangeBreakoutSignalGenerator.evaluatePersistableAtOpen(symbol, uid, null, "LIVE", now, pollTimeframe));
        if (isSymbolAllowed(StrategyKeys.EMA_TREND_FOLLOW, symbol))
            out.add(emaTrendFollowingSignalGenerator.evaluatePersistableAtOpen(symbol, uid, null, "LIVE", now, pollTimeframe));
        if (isSymbolAllowed(StrategyKeys.MOMENTUM_BREAKOUT, symbol))
            out.add(momentumBreakoutSignalGenerator.evaluatePersistableAtOpen(symbol, uid, null, "LIVE", now, pollTimeframe));
        if (isSymbolAllowed(StrategyKeys.CASH_15M_BREAKOUT_TEST, symbol))
            out.add(cashFifteenMinuteBreakoutSignalGenerator.evaluatePersistableAtOpen(symbol, uid, null, "LIVE", now));
        return out;
    }

    /**
     * Returns true if this symbol is in the strategy's bound universe, or if no binding exists
     * (legacy strategies with no universe config are allowed for all symbols).
     */
    private boolean isSymbolAllowed(String strategyKey, String symbol) {
        refreshUniverseCacheIfStale();
        Set<String> allowed = universeCache.get(strategyKey);
        if (allowed == null || allowed.isEmpty()) {
            return true; // no binding configured → legacy allow-all
        }
        return allowed.contains(symbol);
    }

    private void refreshUniverseCacheIfStale() {
        long now = System.currentTimeMillis();
        if (now - universeCacheRefreshedAt < UNIVERSE_CACHE_TTL_MS) {
            return;
        }
        universeCacheRefreshedAt = now;
        try {
            Map<String, Set<String>> fresh = new HashMap<>();
            for (String key : List.of(
                    StrategyKeys.MEAN_REVERSION_RANGE_FADE, StrategyKeys.MEAN_REVERSION_V2,
                    StrategyKeys.OPENING_RANGE_BREAKOUT, StrategyKeys.VWAP_MEAN_REVERSION,
                    StrategyKeys.MOMENTUM_BREAKOUT, StrategyKeys.EMA_TREND_FOLLOW,
                    StrategyKeys.CASH_15M_BREAKOUT_TEST, StrategyKeys.COMMODITIES_10M_BREAKOUT)) {
                List<String> symbols = runtimeBindingRepository.findAllowedSymbolsForStrategyKey(key);
                if (!symbols.isEmpty()) {
                    fresh.put(key, new HashSet<>(symbols));
                }
            }
            universeCache.clear();
            universeCache.putAll(fresh);
            log.debug("universe.cache.refreshed strategies={} bindings={}", fresh.size(),
                    fresh.values().stream().mapToInt(Set::size).sum());
        } catch (Exception ex) {
            log.warn("universe.cache.refresh_failed — keeping stale cache", ex);
        }
    }

    private String resolvePollExecutionMode() {
        if (pollExecutionMode == null || pollExecutionMode.isBlank()) {
            return "PAPER";
        }
        String u = pollExecutionMode.trim().toUpperCase(Locale.ROOT);
        if ("LIVE".equals(u) || "PAPER".equals(u) || "SIMULATED".equals(u)) {
            return u;
        }
        return "PAPER";
    }

    private boolean shouldLog(String key) {
        long now = System.currentTimeMillis();
        Long last = errorLoggedAt.get(key);
        if (last != null && now - last < ERROR_LOG_COOLDOWN_MS) {
            return false;
        }
        errorLoggedAt.put(key, now);
        return true;
    }

    public record EvalStats(int emitted, int failed, List<String> errors) {
    }
}
