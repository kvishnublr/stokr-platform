package com.stokr.trading.service;

import com.stokr.trading.domain.Position;
import com.stokr.trading.dto.TradingDto.*;
import com.stokr.trading.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PositionService {

    private final PositionRepository positionRepository;

    @Transactional(readOnly = true)
    public List<PositionDto> getPositionsByUser(UUID userId) {
        return positionRepository.findByUserIdAndDeletedFalse(userId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PositionDto> getOpenPositionsByUser(UUID userId) {
        return positionRepository.findByUserIdAndStatusAndDeletedFalse(userId, "OPEN")
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PositionDto getPositionBySymbol(UUID userId, String symbol) {
        Position position = positionRepository.findByUserIdAndSymbolAndStatusAndDeletedFalse(userId, symbol, "OPEN")
                .orElseThrow(() -> new IllegalArgumentException("No open position found for symbol: " + symbol));
        return toDto(position);
    }

    @Transactional(readOnly = true)
    public PortfolioSummary getPortfolioSummary(UUID userId) {
        List<Position> allPositions = positionRepository.findByUserIdAndDeletedFalse(userId);
        List<Position> openPositions = positionRepository.findByUserIdAndStatusAndDeletedFalse(userId, "OPEN");
        List<Position> closedPositions = positionRepository.findByUserIdAndStatusAndDeletedFalse(userId, "CLOSED");

        BigDecimal unrealizedPnl = openPositions.stream()
                .map(p -> p.getUnrealizedPnl() != null ? p.getUnrealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal realizedPnl = closedPositions.stream()
                .map(p -> p.getRealizedPnl() != null ? p.getRealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalInvested = openPositions.stream()
                .map(Position::getPositionValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return PortfolioSummary.builder()
                .totalPositions(allPositions.size())
                .openPositions(openPositions.size())
                .closedPositions(closedPositions.size())
                .totalPnl(unrealizedPnl.add(realizedPnl))
                .unrealizedPnl(unrealizedPnl)
                .realizedPnl(realizedPnl)
                .totalInvested(totalInvested)
                .positions(openPositions.stream().map(this::toDto).collect(Collectors.toList()))
                .build();
    }

    private PositionDto toDto(Position p) {
        return PositionDto.builder()
                .id(p.getId())
                .instanceId(p.getInstanceId())
                .userId(p.getUserId())
                .symbol(p.getSymbol())
                .side(p.getSide())
                .quantity(p.getQuantity())
                .avgPrice(p.getAvgPrice())
                .currentPrice(p.getCurrentPrice())
                .pnl(p.getPnl())
                .unrealizedPnl(p.getUnrealizedPnl())
                .realizedPnl(p.getRealizedPnl())
                .exchange(p.getExchange())
                .productType(p.getProductType())
                .status(p.getStatus())
                .openedAt(p.getOpenedAt())
                .closedAt(p.getClosedAt())
                .positionValue(p.getPositionValue())
                .build();
    }
}
