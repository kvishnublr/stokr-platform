package com.stokr.strategy.sdk;

import com.stokr.strategy.sdk.context.DeterministicStrategyContext;
import com.stokr.strategy.sdk.context.IndicatorContext;
import com.stokr.strategy.sdk.context.PositionContext;
import com.stokr.strategy.sdk.context.ReplayContext;
import com.stokr.strategy.sdk.context.RiskContext;

import java.time.Instant;

/**
 * Formal lifecycle for quantitative strategies ??? deterministic hooks only.
 */
public interface ReplaySafeStrategy extends DeterministicStrategy {

    default void onInitialize(DeterministicStrategyContext ctx) {
    }

    @Override
    default void onCandle(String symbol, Instant barOpenUtc, String timeframe) {
    }

    @Override
    default void onTick(String symbol, Instant tickUtc, java.math.BigDecimal price) {
    }

    default void onSignal(String topicPayloadJson) {
    }

    default void onPositionUpdate(PositionContext position) {
    }

    default void onRiskEvent(RiskContext risk, String ruleCode, boolean rejected, String detail) {
    }

    default void onCheckpoint(long journalSequence, String opaqueToken) {
    }

    @Override
    default void onRecovery(StrategyRestoreContext ctx) {
    }

    default void onShutdown(String reason) {
    }

    default void onIndicatorsReady(DeterministicStrategyContext ctx, IndicatorContext indicators) {
    }

    default void onReplayBar(ReplayContext replay, DeterministicStrategyContext ctx) {
    }
}
