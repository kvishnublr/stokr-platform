package com.stokr.strategy.runtime;

import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.meanreversion.MeanReversionSignalGenerator;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
import com.stokr.strategy.telemetry.ScannerExecutionTelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MeanReversionEvaluationService {

    private final MeanReversionSignalGenerator generator;
    private final StrategySignalPipelineService pipelineService;
    private final ScannerExecutionTelemetryService scannerExecutionTelemetryService;

    public void evaluateSymbol(String symbol, UUID userOverride) {
        long t0 = System.nanoTime();
        Throwable err = null;
        boolean emitted = false;
        try {
            StrategySignalEntity entity = generator.evaluatePersistable(symbol, userOverride, null, "LIVE");
            if (entity != null) {
                String cid = CorrelationIdHolder.get();
                if (cid == null || cid.isBlank()) {
                    cid = UUID.randomUUID().toString();
                }
                pipelineService.persistAndDispatch(entity, cid, "SIMULATED");
                emitted = true;
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
    }
}
