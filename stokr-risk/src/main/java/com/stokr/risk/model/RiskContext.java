package com.stokr.risk.model;

import com.stokr.oms.domain.OmsOrder;
import com.stokr.strategy.domain.StrategyExecutionConfig;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Immutable snapshot passed to every {@link com.stokr.risk.api.RiskRule}. Strategies cannot bypass this envelope.
 */
public record RiskContext(
        UUID userId,
        OmsOrder order,
        /** Today's MTM (business-day bucket) when not a backtest order; else zero. */
        BigDecimal dayPnl,
        int openPositionCount,
        LocalTime nowLocal,
        ZoneId zoneId,
        long lastOrderEpochMs,
        /** Non-backtest orders created in the rolling last 60s window (throttle / burst control). */
        int ordersCreatedLast60Seconds,
        /** ATR / close from signal when present; volatility halts use this. */
        BigDecimal signalAtrToCloseRatio,
        /** Global broker operational halt (Redis-backed). */
        boolean brokerCircuitHalt,
        /** Portfolio equity drawdown % from recent snapshots (0–100 scale). */
        BigDecimal portfolioDrawdownPct,
        /** Per-strategy execution config; null for backtests or when no config exists. */
        StrategyExecutionConfig strategyExecutionConfig,
        /** Today's realized PnL for this strategy (IST business date); null for backtests. */
        BigDecimal strategyDailyPnl
) {
    public static RiskContext forTests(UUID userId, OmsOrder order, BigDecimal dayPnl, int openCount, LocalTime lt, ZoneId z, long epochMs) {
        return new RiskContext(
                userId,
                order,
                dayPnl,
                openCount,
                lt,
                z,
                epochMs,
                0,
                null,
                false,
                BigDecimal.ZERO,
                null,
                null
        );
    }
}
