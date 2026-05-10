package com.stokr.strategy.runtime;

import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.meanreversion.MeanReversionSignalGenerator;
import com.stokr.strategy.pipeline.StrategySignalPipelineService;
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

    public void evaluateSymbol(String symbol, UUID userOverride) {
        StrategySignalEntity entity = generator.evaluatePersistable(symbol, userOverride, null, "LIVE");
        if (entity == null) {
            return;
        }
        String cid = CorrelationIdHolder.get();
        if (cid == null || cid.isBlank()) {
            cid = UUID.randomUUID().toString();
        }
        pipelineService.persistAndDispatch(entity, cid, "SIMULATED");
    }
}
