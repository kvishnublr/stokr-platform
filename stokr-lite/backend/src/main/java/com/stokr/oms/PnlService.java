package com.stokr.oms;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PnlService {

    private final PositionService positionService;

    public Map<String, Object> getDeploymentPnl(Long deploymentId) {
        List<Position> positions = positionService.getPositions(deploymentId);

        BigDecimal totalRealized = positions.stream()
                .map(Position::getRealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalUnrealized = positions.stream()
                .map(Position::getUnrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return Map.of(
                "realizedPnl", totalRealized,
                "unrealizedPnl", totalUnrealized,
                "totalPnl", totalRealized.add(totalUnrealized),
                "openPositions", positionService.getOpenPositionCount(deploymentId)
        );
    }
}
