package com.stokr.oms;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PositionService {

    private final PositionRepository positionRepository;

    @Transactional
    public void updatePosition(Long deploymentId, String symbol, String side,
                                int fillQty, BigDecimal fillPrice) {
        Position position = positionRepository
                .findByDeploymentIdAndSymbol(deploymentId, symbol)
                .orElse(Position.builder()
                        .deploymentId(deploymentId)
                        .symbol(symbol)
                        .build());

        int signedQty = "BUY".equalsIgnoreCase(side) ? fillQty : -fillQty;
        int oldQty = position.getQuantity();
        int newQty = oldQty + signedQty;

        if (oldQty == 0 || (oldQty > 0 && signedQty > 0) || (oldQty < 0 && signedQty < 0)) {
            // Adding to position - weighted average
            BigDecimal totalCost = position.getAvgPrice()
                    .multiply(BigDecimal.valueOf(Math.abs(oldQty)))
                    .add(fillPrice.multiply(BigDecimal.valueOf(fillQty)));
            int totalQty = Math.abs(oldQty) + fillQty;
            position.setAvgPrice(totalCost.divide(BigDecimal.valueOf(totalQty), 4, RoundingMode.HALF_UP));
        } else {
            // Reducing/closing position - compute realized PnL
            int closedQty = Math.min(Math.abs(oldQty), fillQty);
            BigDecimal pnl = fillPrice.subtract(position.getAvgPrice())
                    .multiply(BigDecimal.valueOf(oldQty > 0 ? closedQty : -closedQty));
            position.setRealizedPnl(position.getRealizedPnl().add(pnl));
        }

        position.setQuantity(newQty);
        positionRepository.save(position);
        log.debug("Position updated: {} {} qty={} -> {}", symbol, side, oldQty, newQty);
    }

    public List<Position> getPositions(Long deploymentId) {
        return positionRepository.findByDeploymentId(deploymentId);
    }

    public List<Position> getOpenPositions(Long deploymentId) {
        return positionRepository.findByDeploymentIdAndQuantityNot(deploymentId, 0);
    }

    public int getOpenPositionCount(Long deploymentId) {
        return getOpenPositions(deploymentId).size();
    }
}
