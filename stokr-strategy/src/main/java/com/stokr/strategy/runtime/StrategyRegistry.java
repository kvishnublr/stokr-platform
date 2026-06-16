package com.stokr.strategy.runtime;

import com.stokr.strategy.engine.TradingStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class StrategyRegistry {

    private final Map<String, TradingStrategy> strategies;

    public StrategyRegistry(List<TradingStrategy> strategies) {
        this.strategies = strategies.stream()
                .collect(Collectors.toMap(TradingStrategy::key, Function.identity()));
    }

    public TradingStrategy get(String key) {
        return strategies.get(key);
    }
}
