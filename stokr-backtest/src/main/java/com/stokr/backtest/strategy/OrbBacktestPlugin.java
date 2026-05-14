package com.stokr.backtest.strategy;

import com.stokr.backtest.execution.BacktestEvaluationContext;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.keys.StrategyKeys;
import com.stokr.strategy.openingrange.OpeningRangeBreakoutSignalGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrbBacktestPlugin implements BacktestStrategyPlugin {

    private final OpeningRangeBreakoutSignalGenerator generator;

    @Override
    public String strategyKey() {
        return StrategyKeys.OPENING_RANGE_BREAKOUT;
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
                stepTimeframe
        );
    }
}
