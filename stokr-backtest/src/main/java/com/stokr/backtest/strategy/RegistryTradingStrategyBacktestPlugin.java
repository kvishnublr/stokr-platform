package com.stokr.backtest.strategy;

import com.stokr.backtest.execution.BacktestEvaluationContext;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.runtime.StrategyRegistry;
import com.stokr.strategy.service.StrategySignalEntityMapper;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Replays catalog {@link TradingStrategy} beans bar-by-bar using point-in-time candles via {@link StrategyContext#asOf()}.
 */
public class RegistryTradingStrategyBacktestPlugin implements BacktestStrategyPlugin {

    private final String strategyKey;
    private final StrategyRegistry strategyRegistry;

    public RegistryTradingStrategyBacktestPlugin(String strategyKey, StrategyRegistry strategyRegistry) {
        this.strategyKey = strategyKey;
        this.strategyRegistry = strategyRegistry;
    }

    @Override
    public String strategyKey() {
        return strategyKey;
    }

    @Override
    public StrategySignalEntity evaluateAtOpen(
            BacktestEvaluationContext ctx,
            MarketdataCandle bar,
            int barIndex,
            String stepTimeframe
    ) {
        TradingStrategy strategy = strategyRegistry.get(strategyKey);
        if (strategy == null || bar == null || bar.getOpenTime() == null) {
            return null;
        }
        BigDecimal last = bar.getClosePrice() != null ? bar.getClosePrice() : BigDecimal.ZERO;
        StrategyContext sc = new StrategyContext(ctx.execution().symbol(), bar.getOpenTime(), Map.of(), last);
        StrategySignal signal = strategy.evaluate(sc);
        if (signal == null || signal.type() == SignalType.HOLD) {
            return null;
        }
        StrategySignalEntity entity = StrategySignalEntityMapper.baseEntity(
                signal,
                strategyKey,
                ctx.execution().symbol(),
                bar.getOpenTime(),
                ctx.execution().userId(),
                "BACKTEST",
                "2.0.0"
        );
        entity.setBacktestRunId(ctx.execution().runId());
        return entity;
    }
}
