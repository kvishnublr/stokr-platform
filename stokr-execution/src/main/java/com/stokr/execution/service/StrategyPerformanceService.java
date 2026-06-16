package com.stokr.execution.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.domain.OmsTrade;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.OmsTradeRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StrategyPerformanceService {

    private final OmsOrderRepository omsOrderRepository;
    private final OmsTradeRepository omsTradeRepository;
    private final PortfolioPositionRepository portfolioPositionRepository;

    @Transactional(readOnly = true)
    public StrategyPerformanceReport getPerformance(UUID userId, String executionMode, Instant startTime, Instant endTime) {
        log.info("strategy.performance.fetch userId={} mode={} range={}..{}",
                userId, executionMode, startTime, endTime);

        ExecutionMode mode = parseMode(executionMode);

        List<OmsOrder> orders = omsOrderRepository.findByUserIdAndExecutionModeAndCreatedAtBetweenAndDeletedFalse(
                userId, mode, startTime, endTime);

        List<OmsTrade> trades = omsTradeRepository.findByOrderUserIdAndCreatedAtBetweenAndDeletedFalse(
                userId, startTime, endTime);

        // Group by strategy
        Map<String, StrategyMetrics> strategyMetrics = new LinkedHashMap<>();

        for (OmsOrder order : orders) {
            String strategy = order.getStrategyKey() != null ? order.getStrategyKey() : "UNKNOWN";
            strategyMetrics.computeIfAbsent(strategy, k ->
                    StrategyMetrics.builder()
                            .strategyKey(strategy)
                            .executionMode(mode.name())
                            .totalOrders(0)
                            .filledOrders(0)
                            .rejectedOrders(0)
                            .totalTrades(0)
                            .winningTrades(0)
                            .realizedPnL(BigDecimal.ZERO)
                            .avgSlippageBps(BigDecimal.ZERO)
                            .avgLatencyMs(0.0)
                            .fillRatePercent(0.0)
                            .winRatePercent(0.0)
                            .build()
            ).addOrder(order);
        }

        for (OmsTrade trade : trades) {
            String strategy = trade.getOrder().getStrategyKey() != null ?
                    trade.getOrder().getStrategyKey() : "UNKNOWN";
            if (strategyMetrics.containsKey(strategy)) {
                strategyMetrics.get(strategy).addTrade(trade);
            }
        }

        // Finalize all strategy metrics (calculate averages)
        for (StrategyMetrics metrics : strategyMetrics.values()) {
            metrics.computeMetrics();
        }

        // Calculate aggregates
        StrategyPerformanceReport.Aggregate aggregate = calculateAggregate(strategyMetrics.values());

        return StrategyPerformanceReport.builder()
                .userId(userId)
                .executionMode(mode.name())
                .startTime(startTime)
                .endTime(endTime)
                .aggregate(aggregate)
                .strategies(new ArrayList<>(strategyMetrics.values()))
                .build();
    }

    private StrategyPerformanceReport.Aggregate calculateAggregate(Collection<StrategyMetrics> metrics) {
        if (metrics.isEmpty()) {
            return StrategyPerformanceReport.Aggregate.empty();
        }

        long totalOrders = metrics.stream().mapToLong(m -> m.totalOrders).sum();
        long filledOrders = metrics.stream().mapToLong(m -> m.filledOrders).sum();
        long rejectedOrders = metrics.stream().mapToLong(m -> m.rejectedOrders).sum();
        long totalTrades = metrics.stream().mapToLong(m -> m.totalTrades).sum();

        BigDecimal totalPnL = metrics.stream()
                .map(m -> m.realizedPnL)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgSlippage = metrics.stream()
                .map(m -> m.avgSlippageBps)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(Math.max(1, metrics.size())), 2, RoundingMode.HALF_UP);

        double avgLatency = metrics.stream()
                .mapToDouble(m -> m.avgLatencyMs)
                .average()
                .orElse(0.0);

        double fillRate = totalOrders > 0 ? (filledOrders * 100.0) / totalOrders : 0.0;

        return StrategyPerformanceReport.Aggregate.builder()
                .totalOrders(totalOrders)
                .filledOrders(filledOrders)
                .rejectedOrders(rejectedOrders)
                .pendingOrders(totalOrders - filledOrders - rejectedOrders)
                .totalTrades(totalTrades)
                .fillRatePercent(fillRate)
                .realizedPnL(totalPnL)
                .avgSlippageBps(avgSlippage)
                .avgLatencyMs(avgLatency)
                .winRatePercent(calculateWinRate(metrics))
                .build();
    }

    private double calculateWinRate(Collection<StrategyMetrics> metrics) {
        long winningTrades = metrics.stream()
                .mapToLong(m -> m.winningTrades)
                .sum();
        long totalTrades = metrics.stream()
                .mapToLong(m -> m.totalTrades)
                .sum();

        return totalTrades > 0 ? (winningTrades * 100.0) / totalTrades : 0.0;
    }

    private ExecutionMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return ExecutionMode.SIMULATED;
        }
        try {
            return ExecutionMode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ExecutionMode.SIMULATED;
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class StrategyMetrics {
        private String strategyKey;
        private String executionMode;
        private long totalOrders;
        private long filledOrders;
        private long rejectedOrders;
        private long totalTrades;
        private long winningTrades;
        private BigDecimal realizedPnL;
        private BigDecimal avgSlippageBps;
        private double avgLatencyMs;
        private double fillRatePercent;
        private double winRatePercent;

        public void addOrder(OmsOrder order) {
            this.totalOrders++;
            if (order.getState() == OrderState.FILLED || order.getState() == OrderState.PARTIALLY_FILLED) {
                this.filledOrders++;
            } else if (order.getState() == OrderState.REJECTED || order.getState() == OrderState.FAILED) {
                this.rejectedOrders++;
            }
        }

        public void addTrade(OmsTrade trade) {
            this.totalTrades++;
            if (trade.getExecution() != null && trade.getExecution().getSlippageBps() != null) {
                this.avgSlippageBps = this.avgSlippageBps.add(trade.getExecution().getSlippageBps());
            }
            if (trade.getExecution() != null && trade.getExecution().getLatencyMs() != null) {
                this.avgLatencyMs += trade.getExecution().getLatencyMs();
            }
        }

        public void computeMetrics() {
            if (this.totalOrders > 0) {
                this.fillRatePercent = (this.filledOrders * 100.0) / this.totalOrders;
            }
            if (this.totalTrades > 0) {
                this.avgSlippageBps = this.avgSlippageBps.divide(BigDecimal.valueOf(this.totalTrades), 2, RoundingMode.HALF_UP);
                this.avgLatencyMs = this.avgLatencyMs / this.totalTrades;
            }
        }
    }

    @lombok.Data
    @lombok.Builder
    public static class StrategyPerformanceReport {
        private UUID userId;
        private String executionMode;
        private Instant startTime;
        private Instant endTime;
        private Aggregate aggregate;
        private List<StrategyMetrics> strategies;

        @lombok.Data
        @lombok.Builder
        public static class Aggregate {
            private long totalOrders;
            private long filledOrders;
            private long rejectedOrders;
            private long pendingOrders;
            private long totalTrades;
            private double fillRatePercent;
            private BigDecimal realizedPnL;
            private BigDecimal avgSlippageBps;
            private double avgLatencyMs;
            private double winRatePercent;

            public static Aggregate empty() {
                return Aggregate.builder()
                        .totalOrders(0)
                        .filledOrders(0)
                        .rejectedOrders(0)
                        .pendingOrders(0)
                        .totalTrades(0)
                        .fillRatePercent(0.0)
                        .realizedPnL(BigDecimal.ZERO)
                        .avgSlippageBps(BigDecimal.ZERO)
                        .avgLatencyMs(0.0)
                        .winRatePercent(0.0)
                        .build();
            }
        }
    }
}
