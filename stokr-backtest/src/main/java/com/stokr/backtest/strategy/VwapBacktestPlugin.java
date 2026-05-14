package com.stokr.backtest.strategy;

import com.stokr.backtest.execution.BacktestEvaluationContext;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.keys.StrategyKeys;
import com.stokr.strategy.vwap.VwapMeanReversionSignalGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VwapBacktestPlugin implements BacktestStrategyPlugin {

    private final VwapMeanReversionSignalGenerator generator;

    @Override
    public String strategyKey() {
        return StrategyKeys.VWAP_MEAN_REVERSION;
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
