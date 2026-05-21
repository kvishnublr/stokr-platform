package com.stokr.strategy.capital;

import com.stokr.oms.domain.OrderState;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.repository.StrategyExecutionConfigRepository;
import com.stokr.strategy.service.StrategyDailyLossTrackerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StrategyCapitalManager {

    private static final Collection<OrderState> OPEN_STATES = List.of(
            OrderState.CREATED, OrderState.VALIDATED, OrderState.RISK_CHECK,
            OrderState.PENDING_SUBMISSION, OrderState.SUBMITTED, OrderState.ACCEPTED,
            OrderState.PARTIALLY_FILLED
    );

    private final StrategyExecutionConfigRepository configRepository;
    private final PortfolioPositionRepository positionRepository;
    private final OmsOrderRepository omsOrderRepository;
    private final StrategyDailyLossTrackerService dailyLossTrackerService;

    public GlobalCapitalSummary globalSummary() {
        List<StrategyExecutionConfig> configs = configRepository.findAllByUserIdIsNullAndDeletedFalseOrderByStrategyKeyAsc();
        List<StrategyCapitalSummary> strategies = configs.stream()
                .map(this::toStrategyCapitalSummary)
                .toList();

        BigDecimal totalAllocated = sum(strategies.stream().map(StrategyCapitalSummary::allocatedCapital).toList());
        BigDecimal totalUtilized = sum(strategies.stream().map(StrategyCapitalSummary::utilizedCapital).toList());
        BigDecimal totalRealized = sum(strategies.stream().map(StrategyCapitalSummary::realizedPnl).toList());
        BigDecimal totalUnrealized = sum(strategies.stream().map(StrategyCapitalSummary::unrealizedPnl).toList());

        return new GlobalCapitalSummary(
                totalAllocated,
                totalUtilized,
                totalAllocated.subtract(totalUtilized),
                totalRealized,
                totalUnrealized,
                (int) strategies.stream().filter(StrategyCapitalSummary::enabled).count(),
                (int) strategies.stream().filter(StrategyCapitalSummary::liveEnabled).count(),
                (int) strategies.stream().filter(StrategyCapitalSummary::emergencyStopEnabled).count(),
                strategies
        );
    }

    public StrategyCapitalSummary summarizeStrategy(String strategyKey) {
        return configRepository.findByUserIdIsNullAndStrategyKeyAndDeletedFalse(strategyKey)
                .map(this::toStrategyCapitalSummary)
                .orElseThrow(() -> new com.stokr.common.exception.NotFoundException(
                        "StrategyExecutionConfig not found: " + strategyKey));
    }

    private StrategyCapitalSummary toStrategyCapitalSummary(StrategyExecutionConfig cfg) {
        List<PortfolioPosition> positions = positionRepository
                .findByStrategyKeyAndDeletedFalse(cfg.getStrategyKey());

        BigDecimal realizedPnl = positions.stream()
                .map(p -> p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unrealizedPnl = positions.stream()
                .map(p -> p.getUnrealizedPnl() != null ? p.getUnrealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long openPositions = positions.stream()
                .filter(p -> p.getQuantity() != null && p.getQuantity().compareTo(BigDecimal.ZERO) != 0)
                .count();

        BigDecimal allocated = cfg.getAllocatedCapital() != null ? cfg.getAllocatedCapital() : BigDecimal.ZERO;
        BigDecimal utilized = positions.stream()
                .filter(p -> p.getQuantity() != null && p.getQuantity().compareTo(BigDecimal.ZERO) != 0)
                .map(p -> {
                    BigDecimal qty = p.getQuantity().abs();
                    BigDecimal price = p.getAvgPrice() != null ? p.getAvgPrice() : BigDecimal.ZERO;
                    return qty.multiply(price);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new StrategyCapitalSummary(
                cfg.getStrategyKey(),
                allocated,
                utilized,
                allocated.subtract(utilized),
                realizedPnl,
                unrealizedPnl,
                cfg.getMaxPositions(),
                (int) openPositions,
                cfg.isLiveEnabled(),
                cfg.isEmergencyStopEnabled(),
                cfg.isEnabled()
        );
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream()
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
