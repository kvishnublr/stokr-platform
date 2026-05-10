package com.stokr.backtest.strategy;

import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.keys.StrategyKeys;
import com.stokr.strategy.meanreversion.MeanReversionSignalGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MeanReversionBacktestPlugin implements BacktestStrategyPlugin {

    private final MeanReversionSignalGenerator generator;

    @Override
    public String strategyKey() {
        return StrategyKeys.MEAN_REVERSION_RANGE_FADE;
    }

    @Override
    public StrategySignalEntity evaluateAtOpen(
            String symbol,
            UUID userId,
            UUID runId,
            String pipeline,
            Instant barOpen,
            String timeframe
    ) {
        return generator.evaluatePersistableAtOpen(symbol, userId, runId, pipeline, barOpen);
    }
}
