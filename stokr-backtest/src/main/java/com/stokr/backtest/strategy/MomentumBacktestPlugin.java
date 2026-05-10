package com.stokr.backtest.strategy;

import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.keys.StrategyKeys;
import com.stokr.strategy.momentum.MomentumBreakoutSignalGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MomentumBacktestPlugin implements BacktestStrategyPlugin {

    private final MomentumBreakoutSignalGenerator generator;

    @Override
    public String strategyKey() {
        return StrategyKeys.MOMENTUM_BREAKOUT;
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
        return generator.evaluatePersistableAtOpen(symbol, userId, runId, pipeline, barOpen, timeframe);
    }
}
