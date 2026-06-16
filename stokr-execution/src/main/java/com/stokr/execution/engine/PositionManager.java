package com.stokr.execution.engine;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory position manager.
 * Tracks active positions with real-time MTM updates.
 */
@Slf4j
public class PositionManager {

    private final Map<String, Position> positions = new HashMap<>();
    private final UUID userId;

    public PositionManager(UUID userId) {
        this.userId = userId;
    }

    /**
     * Add or update position from filled order.
     */
    public Position addOrUpdate(String symbol, String side, BigDecimal quantity,
                                BigDecimal fillPrice, Instant entryTime) {
        Position pos = positions.computeIfAbsent(symbol, s -> new Position(symbol, userId));

        if ("BUY".equals(side)) {
            pos.addLongQty(quantity, fillPrice);
        } else {
            pos.addShortQty(quantity, fillPrice);
        }

        pos.entryTime = entryTime;
        log.info("position.add_update userId={} symbol={} side={} qty={} price={}",
                userId, symbol, side, quantity, fillPrice);

        return pos;
    }

    /**
     * Update position LTP (for real-time MTM).
     */
    public Position updateLtp(String symbol, BigDecimal ltp) {
        Position pos = positions.get(symbol);
        if (pos != null) {
            pos.updateLtp(ltp);
        }
        return pos;
    }

    /**
     * Close position (full or partial).
     */
    public Position closePosition(String symbol, String side, BigDecimal quantity,
                                  BigDecimal exitPrice, Instant exitTime) {
        Position pos = positions.get(symbol);
        if (pos == null) {
            log.warn("position.close.not_found userId={} symbol={}", userId, symbol);
            return null;
        }

        BigDecimal realizedPnl = "BUY".equals(side)
                ? pos.closeLongQty(quantity, exitPrice)
                : pos.closeShortQty(quantity, exitPrice);

        pos.realizedPnl = pos.realizedPnl.add(realizedPnl);
        pos.exitTime = exitTime;

        if (pos.getNetQty().compareTo(BigDecimal.ZERO) == 0) {
            positions.remove(symbol);
            log.info("position.closed userId={} symbol={} realizedPnl={}", userId, symbol, realizedPnl);
        }

        return pos;
    }

    /**
     * Get position by symbol.
     */
    public Position getPosition(String symbol) {
        return positions.get(symbol);
    }

    /**
     * Get all positions.
     */
    public List<Position> getAllPositions() {
        return List.copyOf(positions.values());
    }

    /**
     * Get total unrealized P&L across all positions.
     */
    public BigDecimal getTotalUnrealizedPnl() {
        return positions.values().stream()
                .map(Position::getUnrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Get total realized P&L.
     */
    public BigDecimal getTotalRealizedPnl() {
        return positions.values().stream()
                .map(p -> p.realizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Position snapshot.
     */
    @Data
    public static class Position {
        private String symbol;
        private UUID userId;
        private BigDecimal longQty = BigDecimal.ZERO;
        private BigDecimal longAvgPrice = BigDecimal.ZERO;
        private BigDecimal shortQty = BigDecimal.ZERO;
        private BigDecimal shortAvgPrice = BigDecimal.ZERO;
        private BigDecimal ltp = BigDecimal.ZERO;
        private BigDecimal realizedPnl = BigDecimal.ZERO;
        private Instant entryTime;
        private Instant exitTime;

        public Position(String symbol, UUID userId) {
            this.symbol = symbol;
            this.userId = userId;
        }

        void addLongQty(BigDecimal qty, BigDecimal price) {
            BigDecimal newQty = longQty.add(qty);
            longAvgPrice = newQty.compareTo(BigDecimal.ZERO) > 0
                    ? longAvgPrice.multiply(longQty).add(price.multiply(qty)).divide(newQty, 8, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            longQty = newQty;
        }

        void addShortQty(BigDecimal qty, BigDecimal price) {
            BigDecimal newQty = shortQty.add(qty);
            shortAvgPrice = newQty.compareTo(BigDecimal.ZERO) > 0
                    ? shortAvgPrice.multiply(shortQty).add(price.multiply(qty)).divide(newQty, 8, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            shortQty = newQty;
        }

        BigDecimal closeLongQty(BigDecimal qty, BigDecimal exitPrice) {
            BigDecimal closed = qty.min(longQty);
            BigDecimal pnl = closed.multiply(exitPrice.subtract(longAvgPrice));
            longQty = longQty.subtract(closed);
            return pnl;
        }

        BigDecimal closeShortQty(BigDecimal qty, BigDecimal exitPrice) {
            BigDecimal closed = qty.min(shortQty);
            BigDecimal pnl = closed.multiply(shortAvgPrice.subtract(exitPrice));
            shortQty = shortQty.subtract(closed);
            return pnl;
        }

        void updateLtp(BigDecimal newLtp) {
            this.ltp = newLtp;
        }

        BigDecimal getNetQty() {
            return longQty.subtract(shortQty);
        }

        BigDecimal getUnrealizedPnl() {
            BigDecimal longPnl = longQty.multiply(ltp.subtract(longAvgPrice));
            BigDecimal shortPnl = shortQty.multiply(shortAvgPrice.subtract(ltp));
            return longPnl.add(shortPnl);
        }

        BigDecimal getPositionValue() {
            return getNetQty().multiply(ltp);
        }
    }
}
