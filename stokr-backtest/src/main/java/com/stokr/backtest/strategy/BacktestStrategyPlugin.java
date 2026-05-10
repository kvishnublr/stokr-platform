package com.stokr.backtest.strategy;

import com.stokr.strategy.domain.StrategySignalEntity;

import java.time.Instant;
import java.util.UUID;

public interface BacktestStrategyPlugin {

    String strategyKey();

    StrategySignalEntity evaluateAtOpen(
            String symbol,
            UUID userId,
            UUID runId,
            String pipeline,
            Instant barOpen,
            String timeframe
    );
}
