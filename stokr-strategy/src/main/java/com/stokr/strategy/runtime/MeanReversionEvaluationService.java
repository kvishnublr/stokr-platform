package com.stokr.strategy.runtime;

import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.runtime.ExecutionPipelineRuntimeReadinessService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.meanreversion.MeanReversionSignalGenerator;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.telemetry.ScannerExecutionTelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MeanReversionEvaluationService {

    private final MeanReversionSignalGenerator generator;
    private final StrategySignalPipelineService pipelineService;
    private final ScannerExecutionTelemetryService scannerExecutionTelemetryService;
    private final ExecutionPipelineRuntimeReadinessService executionPipelineRuntimeReadinessService;

    /**
     * Execution mode string for {@link com.stokr.common.pipeline.messages.SignalPersistedMessage} /
     * {@link com.stokr.execution.pipeline.OrderIntentProcessor#parseMode(String)}: PAPER, LIVE, or SIMULATED.
     */
    @Value("${stokr.strategy.poll-execution-mode:PAPER}")
    private String pollExecutionMode;

    /**
     * @return {@link SymbolEvalResult#EMITTED} if a non-hold signal was persisted and dispatched
     */
    public SymbolEvalResult evaluateSymbol(String symbol, UUID userOverride) {
        long t0 = System.nanoTime();
        Throwable err = null;
        boolean emitted = false;
        try {
            StrategySignalEntity entity = generator.evaluatePersistable(symbol, userOverride, null, "LIVE");
            if (entity != null) {
                String executionMode = resolvePollExecutionMode();
                if (!executionPipelineRuntimeReadinessService.canRouteExecutionMode(executionMode)) {
                    throw new IllegalStateException(
                            "Execution pipeline disabled: Rabbit listeners are OFF for " + executionMode + " signal routing."
                    );
                }
                String cid = CorrelationIdHolder.get();
                if (cid == null || cid.isBlank()) {
                    cid = UUID.randomUUID().toString();
                }
                StrategySignalEntity persisted = pipelineService.persistAndDispatch(entity, cid, executionMode);
                emitted = persisted != null;
            }
        } catch (Exception ex) {
            err = ex;
            log.warn("strategy.eval.failed symbol={}", symbol, ex);
        } finally {
            scannerExecutionTelemetryService.recordEvaluationComplete(
                    symbol,
                    System.nanoTime() - t0,
                    emitted,
                    err
            );
        }
        if (err != null) {
            return SymbolEvalResult.FAILED;
        }
        return emitted ? SymbolEvalResult.EMITTED : SymbolEvalResult.NO_SIGNAL;
    }

    private String resolvePollExecutionMode() {
        if (pollExecutionMode == null || pollExecutionMode.isBlank()) {
            return "PAPER";
        }
        String u = pollExecutionMode.trim().toUpperCase();
        if ("LIVE".equals(u) || "PAPER".equals(u) || "SIMULATED".equals(u)) {
            return u;
        }
        log.warn("strategy.poll_execution_mode.invalid value={} - using PAPER", pollExecutionMode);
        return "PAPER";
    }

    public enum SymbolEvalResult {
        NO_SIGNAL,
        EMITTED,
        FAILED
    }
}
