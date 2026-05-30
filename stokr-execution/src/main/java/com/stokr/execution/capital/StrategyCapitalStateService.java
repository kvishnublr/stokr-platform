package com.stokr.execution.capital;

import com.stokr.oms.domain.OrderState;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.service.StrategyExecutionConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StrategyCapitalStateService {

    private static final Collection<OrderState> PENDING_STATES = List.of(
            OrderState.CREATED, OrderState.VALIDATED, OrderState.RISK_CHECK,
            OrderState.PENDING_SUBMISSION, OrderState.SUBMITTED, OrderState.ACCEPTED,
            OrderState.PARTIALLY_FILLED
    );

    private final StrategyExecutionConfigService configService;
    private final PortfolioPositionRepository positionRepository;
    private final OmsOrderRepository omsOrderRepository;
    private final StrategyCapitalReservationRepository reservationRepository;

    public StrategyCapitalSnapshot snapshot(String strategyKey, UUID userId) {
        StrategyExecutionConfig cfg = resolveConfig(strategyKey, userId);
        BigDecimal allocated = cfg.getAllocatedCapital() != null ? cfg.getAllocatedCapital() : BigDecimal.ZERO;

        List<PortfolioPosition> positions = positionRepository.findRealByStrategyKeyAndDeletedFalse(strategyKey);
        BigDecimal deployed = positions.stream()
                .filter(p -> p.getQuantity() != null && p.getQuantity().compareTo(BigDecimal.ZERO) != 0)
                .map(this::positionNotional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal reserved = reservationRepository.sumActiveReserved(strategyKey);
        if (reserved == null) {
            reserved = BigDecimal.ZERO;
        }

        BigDecimal pendingNotional = omsOrderRepository.sumPendingNotionalByStrategy(strategyKey, PENDING_STATES);
        if (pendingNotional == null) {
            pendingNotional = BigDecimal.ZERO;
        }
        BigDecimal unrealized = positions.stream()
                .map(p -> p.getUnrealizedPnl() != null ? p.getUnrealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal realized = positions.stream()
                .map(p -> p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long openPositions = positions.stream()
                .filter(p -> p.getQuantity() != null && p.getQuantity().compareTo(BigDecimal.ZERO) != 0)
                .count();
        long pendingOrders = reservationRepository.countByStrategyKeyAndStatusAndDeletedFalse(strategyKey, "ACTIVE");

        BigDecimal available = allocated.subtract(deployed).subtract(reserved).subtract(pendingNotional);
        if (available.compareTo(BigDecimal.ZERO) < 0) {
            available = BigDecimal.ZERO;
        }

        BigDecimal utilization = allocated.compareTo(BigDecimal.ZERO) > 0
                ? deployed.add(reserved).add(pendingNotional)
                        .divide(allocated, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        return new StrategyCapitalSnapshot(
                strategyKey,
                allocated,
                deployed,
                reserved,
                pendingNotional,
                available,
                unrealized,
                realized,
                openPositions,
                pendingOrders,
                cfg.getMaxPositions(),
                utilization,
                cfg.getSizingMode(),
                cfg.getCapitalUtilizationMode());
    }

    private BigDecimal positionNotional(PortfolioPosition p) {
        BigDecimal qty = p.getQuantity() != null ? p.getQuantity().abs() : BigDecimal.ZERO;
        BigDecimal px = p.getAvgPrice() != null ? p.getAvgPrice() : BigDecimal.ZERO;
        return qty.multiply(px);
    }

    private StrategyExecutionConfig resolveConfig(String strategyKey, UUID userId) {
        Optional<StrategyExecutionConfig> opt = userId != null
                ? configService.getByStrategyKeyForUser(userId, strategyKey)
                : configService.getByStrategyKey(strategyKey);
        return opt.orElseThrow(() -> new IllegalStateException("No execution config for strategy: " + strategyKey));
    }

    public record StrategyCapitalSnapshot(
            String strategyKey,
            BigDecimal allocatedCapital,
            BigDecimal deployedCapital,
            BigDecimal reservedCapital,
            BigDecimal pendingOrderCapital,
            BigDecimal availableCapital,
            BigDecimal unrealizedPnl,
            BigDecimal realizedPnl,
            long openPositions,
            long pendingReservations,
            int maxPositions,
            BigDecimal utilizationPct,
            String sizingMode,
            String capitalUtilizationMode) {}
}
