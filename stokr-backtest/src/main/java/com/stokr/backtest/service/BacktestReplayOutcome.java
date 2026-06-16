package com.stokr.backtest.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BacktestReplayOutcome(
        UUID runId,
        String runStatus,
        String strategyKey,
        String symbol,
        Instant rangeStart,
        Instant rangeEnd,
        String timeframe,
        String executionProfile,
        /** False when metrics/trades have not been persisted yet (e.g. interrupted run). */
        boolean materialized,
        ReplayValidationService.ReplayValidationReport validation,
        MetricsSnapshot metrics,
        List<TradeSnapshot> trades,
        List<EquitySnapshot> equityCurve,
        /** Populated when replay loop telemetry is captured (async jobs + sync replay). */
        ReplayLoopTelemetry loopTelemetry
) {

    public record MetricsSnapshot(
            BigDecimal winRate,
            int totalTrades,
            BigDecimal profitFactor,
            BigDecimal sharpeRatio,
            BigDecimal maxDrawdown,
            BigDecimal avgRr,
            BigDecimal expectancy,
            BigDecimal totalPnl,
            Long avgHoldingTimeSeconds
    ) {
        public static MetricsSnapshot empty() {
            return new MetricsSnapshot(
                    BigDecimal.ZERO,
                    0,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0L
            );
        }
    }

    public record TradeSnapshot(
            UUID id,
            String symbol,
            String side,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal pnl,
            Instant openedAt,
            Instant closedAt,
            Long holdingSeconds
    ) {
    }

    public record EquitySnapshot(
            Instant pointTime,
            BigDecimal cumulativePnl,
            BigDecimal drawdown
    ) {
    }
}
