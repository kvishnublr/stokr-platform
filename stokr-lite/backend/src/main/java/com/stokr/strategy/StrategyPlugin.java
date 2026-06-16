package com.stokr.strategy;

/**
 * Strategy plugin interface. Each strategy implements this to generate signals.
 */
public interface StrategyPlugin {

    String getStrategyType();

    Signal evaluate(MarketContext context, StrategyParams params);
}
