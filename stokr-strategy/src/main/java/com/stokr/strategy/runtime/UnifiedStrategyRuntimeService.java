package com.stokr.strategy.runtime;

import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.runtime.ExecutionPipelineRuntimeReadinessService;
import com.stokr.strategy.cash.CashFifteenMinuteBreakoutSignalGenerator;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.ematrend.EmaTrendFollowingSignalGenerator;
import com.stokr.strategy.meanreversion.MeanReversionSignalGenerator;
import com.stokr.strategy.momentum.MomentumBreakoutSignalGenerator;
import com.stokr.strategy.openingrange.OpeningRangeBreakoutSignalGenerator;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.telemetry.ScannerExecutionTelemetryService;
import com.stokr.strategy.vwap.VwapMeanReversionSignalGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UnifiedStrategyRuntimeService {

    private final MeanReversionSignalGenerator meanReversionSignalGenerator;
    private final EmaTrendFollowingSignalGenerator emaTrendFollowingSignalGenerator;
    private final MomentumBreakoutSignalGenerator momentumBreakoutSignalGenerator;
    private final OpeningRangeBreakoutSignalGenerator openingRangeBreakoutSignalGenerator;
    private final VwapMeanReversionSignalGenerator vwapMeanReversionSignalGenerator;
    private final CashFifteenMinuteBreakoutSignalGenerator cashFifteenMinuteBreakoutSignalGenerator;
    private final StrategySignalPipelineService pipelineService;
    private final ScannerExecutionTelemetryService scannerExecutionTelemetryService;
    private final ExecutionPipelineRuntimeReadinessService executionPipelineRuntimeReadinessService;

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
                    pipelineService.persistAndDispatch(sig, cid, mode);
                    emitted++;
                } catch (Exception ex) {
                    failed++;
                    errors.add(ex.getClass().getSimpleName());
                    log.warn("strategy.runtime.dispatch_failed symbol={} strategy={}", symbol, sig.getStrategyName(), ex);
                }
            }
            return new EvalStats(emitted, failed, errors);
        } catch (Exception ex) {
            failed++;
            errors.add(ex.getClass().getSimpleName());
            log.warn("strategy.runtime.eval_failed symbol={}", symbol, ex);
            return new EvalStats(emitted, failed, errors);
        } finally {
            scannerExecutionTelemetryService.recordEvaluationComplete(symbol, System.nanoTime() - t0, emitted > 0, null);
        }
    }

    private List<StrategySignalEntity> evaluateCandidates(String symbol) {
        Instant now = Instant.now();
        UUID uid = systemUserId;
        List<StrategySignalEntity> out = new ArrayList<>(5);
        out.add(meanReversionSignalGenerator.evaluatePersistable(symbol, uid, null, "LIVE"));
        out.add(vwapMeanReversionSignalGenerator.evaluatePersistableAtOpen(symbol, uid, null, "LIVE", now, pollTimeframe));
        out.add(openingRangeBreakoutSignalGenerator.evaluatePersistableAtOpen(symbol, uid, null, "LIVE", now, pollTimeframe));
        out.add(emaTrendFollowingSignalGenerator.evaluatePersistableAtOpen(symbol, uid, null, "LIVE", now, pollTimeframe));
        out.add(momentumBreakoutSignalGenerator.evaluatePersistableAtOpen(symbol, uid, null, "LIVE", now, pollTimeframe));
        out.add(cashFifteenMinuteBreakoutSignalGenerator.evaluatePersistableAtOpen(symbol, uid, null, "LIVE", now));
        return out;
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

    public record EvalStats(int emitted, int failed, List<String> errors) {
    }
}
