package com.stokr.execution.engine;

import com.stokr.execution.adapter.ExecutionAdapter;
import com.stokr.marketdata.domain.MarketdataTick;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Real-time P&L calculation engine.
 * Updates on every tick, every fill, every position change.
 */
@RequiredArgsConstructor
@Slf4j
public class PnLEngine {

    private final PositionManager positionManager;
    private BigDecimal startingCapital = BigDecimal.ZERO;
    private BigDecimal totalRealizedPnl = BigDecimal.ZERO;
    private Consumer<PnLSnapshot> updateListener;

    /**
     * Initialize with starting capital.
     */
    public void initialize(BigDecimal startingCapital) {
        this.startingCapital = startingCapital;
        log.info("pnl_engine.initialized capital={}", startingCapital);
    }

    /**
     * Called when market tick arrives.
     * Updates position LTPs and calculates new P&L snapshot.
     */
    public PnLSnapshot onMarketTick(MarketdataTick tick) {
        PositionManager.Position pos = positionManager.updateLtp(tick.getSymbol(), tick.getPrice());
        if (pos != null) {
            return calculateSnapshot();
        }
        return null;
    }

    /**
     * Called when order gets filled.
     * Updates positions and calculates P&L impact.
     */
    public PnLSnapshot onFill(ExecutionAdapter.FillRecord fill) {
        // Position updates are handled by PositionManager
        // Here we just recalculate snapshot
        return calculateSnapshot();
    }

    /**
     * Called when position closes.
     * Records realized P&L and updates snapshot.
     */
    public PnLSnapshot onPositionClosed(String symbol, String side, BigDecimal quantity,
                                        BigDecimal exitPrice) {
        PositionManager.Position pos = positionManager.closePosition(
                symbol, side, quantity, exitPrice, Instant.now()
        );

        if (pos != null) {
            totalRealizedPnl = positionManager.getTotalRealizedPnl();
        }

        return calculateSnapshot();
    }

    /**
     * Calculate complete P&L snapshot.
     */
    public PnLSnapshot calculateSnapshot() {
        BigDecimal unrealizedPnl = positionManager.getTotalUnrealizedPnl();
        BigDecimal realizedPnl = positionManager.getTotalRealizedPnl();
        BigDecimal totalPnl = unrealizedPnl.add(realizedPnl);

        BigDecimal equityValue = startingCapital.add(totalPnl);
        BigDecimal pnlPercent = startingCapital.compareTo(BigDecimal.ZERO) > 0
                ? totalPnl.divide(startingCapital, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        BigDecimal grossExposure = positionManager.getAllPositions().stream()
                .map(p -> p.getPositionValue().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal netExposure = positionManager.getAllPositions().stream()
                .map(PositionManager.Position::getPositionValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        PnLSnapshot snapshot = PnLSnapshot.builder()
                .timestamp(Instant.now())
                .unrealizedPnl(unrealizedPnl)
                .realizedPnl(realizedPnl)
                .totalPnl(totalPnl)
                .pnlPercent(pnlPercent)
                .mtm(unrealizedPnl)  // Mark-to-market = unrealized
                .grossExposure(grossExposure)
                .netExposure(netExposure)
                .equityValue(equityValue)
                .positionsCount(positionManager.getAllPositions().size())
                .build();

        // Broadcast to subscribers
        if (updateListener != null) {
            updateListener.accept(snapshot);
        }

        return snapshot;
    }

    /**
     * Set listener for P&L updates (for WebSocket broadcasts).
     */
    public void setUpdateListener(Consumer<PnLSnapshot> listener) {
        this.updateListener = listener;
    }

    /**
     * P&L snapshot.
     */
    @Data
    @Builder
    public static class PnLSnapshot {
        private Instant timestamp;
        private BigDecimal unrealizedPnl;
        private BigDecimal realizedPnl;
        private BigDecimal totalPnl;
        private BigDecimal pnlPercent;
        private BigDecimal mtm;
        private BigDecimal grossExposure;
        private BigDecimal netExposure;
        private BigDecimal equityValue;
        private int positionsCount;
    }
}
