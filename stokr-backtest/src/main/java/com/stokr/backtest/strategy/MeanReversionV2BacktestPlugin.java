package com.stokr.backtest.strategy;

import com.stokr.backtest.execution.BacktestEvaluationContext;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.keys.StrategyKeys;
import com.stokr.strategy.meanreversion.MeanReversionV2SignalGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MeanReversionV2BacktestPlugin implements BacktestStrategyPlugin {

    private final MeanReversionV2SignalGenerator generator;

    @Override
    public String strategyKey() {
        return StrategyKeys.MEAN_REVERSION_V2;
    }

    @Override
    public StrategySignalEntity evaluateAtOpen(
            BacktestEvaluationContext ctx,
            MarketdataCandle bar,
            int barIndex,
            String stepTimeframe
    ) {
        return generator.evaluatePersistableAtOpen(
                ctx.execution().symbol(),
                ctx.execution().userId(),
                ctx.execution().runId(),
                ctx.pipeline(),
                bar.getOpenTime(),
                stepTimeframe != null && !stepTimeframe.isBlank() ? stepTimeframe : "1m",
                null
        );
    }
}
