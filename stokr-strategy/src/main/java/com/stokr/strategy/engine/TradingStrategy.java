package com.stokr.strategy.engine;

import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.signals.StrategySignal;

/**
 * Strategies emit signals only; execution is handled elsewhere.
 */
public interface TradingStrategy {

    String key();

    StrategySignal evaluate(StrategyContext context);
}
