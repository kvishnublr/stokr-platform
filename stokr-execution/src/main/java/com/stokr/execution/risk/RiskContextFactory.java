package com.stokr.execution.risk;

import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.service.PortfolioQueryService;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.service.BrokerOperationalCircuitService;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.service.StrategyDailyLossTrackerService;
import com.stokr.strategy.service.StrategyExecutionConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Central factory for {@link RiskContext} so strategies and ad-hoc callers cannot assemble partial / unsafe snapshots.
 */
@Service
@RequiredArgsConstructor
public class RiskContextFactory {

    private final PortfolioQueryService portfolioQueryService;
    private final OmsOrderRepository omsOrderRepository;
    private final BrokerOperationalCircuitService brokerOperationalCircuitService;
    private final StrategyExecutionConfigService strategyExecutionConfigService;
    private final StrategyDailyLossTrackerService strategyDailyLossTrackerService;

    public RiskContext build(UUID userId, OmsOrder order, ZoneId zone, Instant evalInstant, BigDecimal signalAtrToCloseRatio) {
        if (order.getBacktestRunId() != null) {
            return forBacktest(userId, order, zone, evalInstant);
        }
        var overview = portfolioQueryService.overview(userId);
        var dashboard = portfolioQueryService.dashboard(userId, 80);
        Instant since60 = evalInstant.minusSeconds(60);
        int burst = (int) omsOrderRepository.countNonBacktestOrdersSinceExcluding(userId, since60, order.getId());
        String strategyKey = order.getStrategyKey();
        StrategyExecutionConfig stratCfg = strategyKey != null
                ? strategyExecutionConfigService.getByStrategyKey(strategyKey).orElse(null)
                : null;
        BigDecimal stratDailyPnl = strategyKey != null
                ? strategyDailyLossTrackerService.getTodayPnl(strategyKey)
                : null;
        return new RiskContext(
                userId,
                order,
                overview.todayMtm(),
                overview.openPositionCount(),
                LocalTime.ofInstant(evalInstant, zone),
                zone,
                evalInstant.toEpochMilli(),
                burst,
                signalAtrToCloseRatio,
                brokerOperationalCircuitService.isGlobalBrokerHalt(),
                dashboard.maxDrawdownPct(),
                stratCfg,
                stratDailyPnl
        );
    }

    private static RiskContext forBacktest(UUID userId, OmsOrder order, ZoneId zone, Instant evalInstant) {
        return new RiskContext(
                userId,
                order,
                BigDecimal.ZERO,
                0,
                LocalTime.ofInstant(evalInstant, zone),
                zone,
                evalInstant.toEpochMilli(),
                0,
                null,
                false,
                BigDecimal.ZERO,
                null,
                null
        );
    }
}
