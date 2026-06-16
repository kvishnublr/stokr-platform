package com.stokr.backtest.strategy;

import com.stokr.common.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class BacktestStrategyRegistry {

    private final Map<String, BacktestStrategyPlugin> byKey;

    public BacktestStrategyRegistry(List<BacktestStrategyPlugin> plugins) {
        this.byKey = plugins.stream().collect(Collectors.toMap(BacktestStrategyPlugin::strategyKey, Function.identity()));
    }

    public BacktestStrategyPlugin require(String strategyKey) {
        BacktestStrategyPlugin p = byKey.get(strategyKey);
        if (p == null) {
            throw new BadRequestException("Unknown backtest strategy: " + strategyKey);
        }
        return p;
    }
}
