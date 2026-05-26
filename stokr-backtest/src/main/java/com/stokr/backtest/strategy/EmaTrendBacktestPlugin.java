package com.stokr.backtest.strategy;

import com.stokr.backtest.execution.BacktestEvaluationContext;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.keys.StrategyKeys;
import com.stokr.strategy.ematrend.EmaTrendFollowingSignalGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(EmaTrendFollowingSignalGenerator.class)
@RequiredArgsConstructor
public class EmaTrendBacktestPlugin implements BacktestStrategyPlugin {

    private final EmaTrendFollowingSignalGenerator generator;

    @Override
    public String strategyKey() {
        return StrategyKeys.EMA_TREND_FOLLOW;
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
