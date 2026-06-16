package com.stokr.strategy.runtime;

import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StrategyExecutor {

    private final StrategyRegistry registry;

    public StrategySignal execute(String strategyKey, StrategyContext context) {
        TradingStrategy s = registry.get(strategyKey);
        if (s == null) {
            return null;
        }
        return s.evaluate(context);
    }
}
