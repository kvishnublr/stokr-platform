package com.stokr.execution.safety;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.service.PortfolioQueryService;
import com.stokr.strategy.service.StrategyDailyLossTrackerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OmsExposureControlService {

    private final OmsOrderRepository orderRepository;
    private final PortfolioQueryService portfolioQueryService;
    private final StrategyDailyLossTrackerService strategyDailyLossTrackerService;
    private final TradingKillSwitchService killSwitchService;

    @Value("${stokr.oms.exposure.max-daily-loss:50000}")
    private BigDecimal maxDailyLoss;

    @Value("${stokr.oms.exposure.max-loss-per-strategy:20000}")
    private BigDecimal maxLossPerStrategy;

    @Value("${stokr.oms.exposure.max-concurrent-positions:10}")
    private int maxConcurrentPositions;

    @Value("${stokr.oms.exposure.max-exposure-per-symbol:100000}")
    private BigDecimal maxExposurePerSymbol;

    @Value("${stokr.oms.exposure.max-exposure-per-strategy:150000}")
    private BigDecimal maxExposurePerStrategy;

    @Value("${stokr.oms.exposure.max-total-capital-deployed:500000}")
    private BigDecimal maxTotalCapitalDeployed;

    @Value("${stokr.oms.exposure.max-orders-per-minute:20}")
    private int maxOrdersPerMinute;

    @Value("${stokr.oms.exposure.activate-kill-switch-on-breach:false}")
    private boolean activateKillSwitchOnBreach;

    public Optional<LiveSignalStaleGuardService.OmsSafetyViolation> evaluateLive(
            UUID userId,
            OmsOrder draft,
            Instant now) {
        if (draft.getExecutionMode() != ExecutionMode.LIVE) {
            return Optional.empty();
        }

        var overview = portfolioQueryService.overview(userId);
        BigDecimal todayPnl = overview.todayMtm() != null ? overview.todayMtm() : BigDecimal.ZERO;
        if (maxDailyLoss.compareTo(BigDecimal.ZERO) > 0 && todayPnl.compareTo(maxDailyLoss.negate()) < 0) {
            return breach("MAX_DAILY_LOSS", "Daily loss " + todayPnl + " exceeds limit -" + maxDailyLoss);
        }

        if (maxConcurrentPositions > 0 && overview.openPositionCount() >= maxConcurrentPositions) {
            return breach("MAX_CONCURRENT_POSITIONS",
                    "Open positions " + overview.openPositionCount() + " >= " + maxConcurrentPositions);
        }

        String strategy = draft.getStrategyKey();
        if (strategy != null && maxLossPerStrategy.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal stratPnl = strategyDailyLossTrackerService.getTodayPnl(strategy);
            if (stratPnl != null && stratPnl.compareTo(maxLossPerStrategy.negate()) < 0) {
                return breach("MAX_STRATEGY_LOSS",
                        "Strategy " + strategy + " loss " + stratPnl + " exceeds -" + maxLossPerStrategy);
            }
        }

        Instant since60 = now.minusSeconds(60);
        UUID exclude = draft.getId() != null ? draft.getId() : UUID.fromString("00000000-0000-0000-0000-000000000000");
        int burst = (int) orderRepository.countNonBacktestOrdersSinceExcluding(userId, since60, exclude);
        if (maxOrdersPerMinute > 0 && burst >= maxOrdersPerMinute) {
            return breach("MAX_ORDERS_PER_MINUTE", "Order burst " + burst + " >= " + maxOrdersPerMinute);
        }

        BigDecimal notional = estimateNotional(draft);
        if (maxExposurePerSymbol.compareTo(BigDecimal.ZERO) > 0 && notional.compareTo(maxExposurePerSymbol) > 0) {
            return breach("MAX_EXPOSURE_SYMBOL",
                    "Symbol notional " + notional + " > " + maxExposurePerSymbol);
        }
        if (maxExposurePerStrategy.compareTo(BigDecimal.ZERO) > 0 && notional.compareTo(maxExposurePerStrategy) > 0) {
            return breach("MAX_EXPOSURE_STRATEGY",
                    "Strategy notional " + notional + " > " + maxExposurePerStrategy);
        }
        var dashboard = portfolioQueryService.dashboard(userId, 30);
        BigDecimal deployed = sumExposure(dashboard.exposure());
        if (maxTotalCapitalDeployed.compareTo(BigDecimal.ZERO) > 0
                && deployed.add(notional).compareTo(maxTotalCapitalDeployed) > 0) {
            return breach("MAX_TOTAL_CAPITAL",
                    "Total deployed " + deployed.add(notional) + " would exceed " + maxTotalCapitalDeployed);
        }

        return Optional.empty();
    }

    public Map<String, Object> activeLimitsSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxDailyLoss", maxDailyLoss);
        m.put("maxLossPerStrategy", maxLossPerStrategy);
        m.put("maxConcurrentPositions", maxConcurrentPositions);
        m.put("maxExposurePerSymbol", maxExposurePerSymbol);
        m.put("maxExposurePerStrategy", maxExposurePerStrategy);
        m.put("maxTotalCapitalDeployed", maxTotalCapitalDeployed);
        m.put("maxOrdersPerMinute", maxOrdersPerMinute);
        m.put("activateKillSwitchOnBreach", activateKillSwitchOnBreach);
        return m;
    }

    private Optional<LiveSignalStaleGuardService.OmsSafetyViolation> breach(String code, String message) {
        log.error("oms.exposure.BREACH code={} message={}", code, message);
        if (activateKillSwitchOnBreach) {
            killSwitchService.activateOnRiskBreach(code + ": " + message);
        }
        return Optional.of(new LiveSignalStaleGuardService.OmsSafetyViolation(code, message));
    }

    private static BigDecimal estimateNotional(OmsOrder order) {
        BigDecimal px = order.getEntryReferencePrice() != null ? order.getEntryReferencePrice() : BigDecimal.ZERO;
        BigDecimal qty = order.getQuantity() != null ? order.getQuantity() : BigDecimal.ONE;
        return px.multiply(qty);
    }

    private static BigDecimal sumExposure(com.stokr.oms.dto.PortfolioExposureDto exposure) {
        if (exposure == null || exposure.bySymbol() == null) {
            return BigDecimal.ZERO;
        }
        return exposure.bySymbol().stream()
                .map(com.stokr.oms.dto.PortfolioExposureDto.SymbolExposure::exposureNotional)
                .filter(n -> n != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
