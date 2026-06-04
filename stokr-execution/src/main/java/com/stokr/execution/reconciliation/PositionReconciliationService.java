package com.stokr.execution.reconciliation;

import com.stokr.execution.broker.BrokerPositionTruthService;
import com.stokr.execution.broker.BrokerPositionTruthSnapshot;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.PortfolioPositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PositionReconciliationService {

    private final BrokerPositionTruthService brokerPositionTruthService;
    private final PortfolioPositionRepository portfolioPositionRepository;

    /**
     * Reconciliation status for UI dashboard
     */
    public PositionReconciliationStatus getReconciliationStatus(UUID userId) {
        brokerPositionTruthService.syncUser(userId);
        BrokerPositionTruthSnapshot brokerSnapshot = brokerPositionTruthService.snapshot(userId);

        List<PortfolioPosition> omsPositions = portfolioPositionRepository
                .findByUserIdAndDeletedFalse(userId);

        List<GhostPosition> ghosts = findGhostPositions(omsPositions, brokerSnapshot);
        List<BlockingPosition> blocking = findBlockingPositions(omsPositions, ghosts);

        int openOmsLegs = (int) omsPositions.stream()
                .filter(p -> p.getQuantity() != null && p.getQuantity().signum() != 0)
                .count();

        return new PositionReconciliationStatus(
                openOmsLegs,
                ghosts.size(),
                blocking.size(),
                0, // capacity issues - would need strategy service
                brokerSnapshot.brokerConnected() ? "YES" : "NO",
                brokerSnapshot.lastSyncAt() != null ? "VERIFIED" : "PENDING",
                ghosts,
                blocking,
                Instant.now()
        );
    }

    /**
     * Find ghost positions (OMS qty != Broker qty)
     */
    private List<GhostPosition> findGhostPositions(
            List<PortfolioPosition> omsPositions,
            BrokerPositionTruthSnapshot brokerSnapshot) {

        List<GhostPosition> ghosts = new ArrayList<>();

        for (PortfolioPosition omsPos : omsPositions) {
            if (omsPos.getQuantity() == null || omsPos.getQuantity().signum() == 0) {
                continue; // Skip zero positions
            }

            String symbol = omsPos.getSymbol();
            BigDecimal omsQty = omsPos.getQuantity();

            // Find broker position
            BigDecimal brokerQty = BigDecimal.ZERO;
            if (brokerSnapshot.brokerConnected() && brokerSnapshot.lastSyncAt() != null) {
                for (BrokerPositionTruthSnapshot.BrokerTruthPositionRow row : brokerSnapshot.positions()) {
                    if (symbol.equalsIgnoreCase(row.symbol())) {
                        brokerQty = row.brokerQty() != null ? row.brokerQty() : BigDecimal.ZERO;
                        break;
                    }
                }
            }

            // Check if mismatch
            if (omsQty.compareTo(brokerQty) != 0) {
                ghosts.add(new GhostPosition(
                        symbol,
                        omsQty,
                        brokerQty,
                        omsPos.getAverageCost(),
                        omsPos.getId(),
                        "OMS-Broker mismatch: OMS=" + omsQty + ", Broker=" + brokerQty,
                        omsQty.signum() == 0 ? "ZERO_PRICE_GHOST" : "QUANTITY_MISMATCH"
                ));
                log.warn("ghost.position.detected symbol={} omsQty={} brokerQty={} userId={}",
                        symbol, omsQty, brokerQty, omsPos.getUserId());
            }

            // Check for zero-price positions
            if (omsPos.getAverageCost() == null || omsPos.getAverageCost().signum() == 0) {
                ghosts.add(new GhostPosition(
                        symbol,
                        omsQty,
                        brokerQty,
                        omsPos.getAverageCost(),
                        omsPos.getId(),
                        "Zero-price position in OMS (likely from failed fill)",
                        "ZERO_PRICE_GHOST"
                ));
                log.warn("ghost.zero_price_detected symbol={} qty={} userId={}",
                        symbol, omsQty, omsPos.getUserId());
            }
        }

        return ghosts;
    }

    /**
     * Find blocking positions (preventing new LIVE entries)
     */
    private List<BlockingPosition> findBlockingPositions(
            List<PortfolioPosition> omsPositions,
            List<GhostPosition> ghosts) {

        List<BlockingPosition> blocking = new ArrayList<>();
        Set<String> ghostSymbols = ghosts.stream()
                .map(GhostPosition::symbol)
                .collect(Collectors.toSet());

        for (PortfolioPosition pos : omsPositions) {
            if (pos.getQuantity() == null || pos.getQuantity().signum() == 0) {
                continue;
            }

            String reason = null;
            if (ghostSymbols.contains(pos.getSymbol())) {
                reason = "Ghost position blocking LIVE entries";
            } else if (pos.getAverageCost() != null && pos.getAverageCost().signum() == 0) {
                reason = "Zero-price position blocking LIVE entries";
            }

            if (reason != null) {
                blocking.add(new BlockingPosition(
                        pos.getSymbol(),
                        pos.getQuantity(),
                        pos.getAverageCost(),
                        pos.getId(),
                        reason,
                        true
                ));
                log.warn("blocking.position.detected symbol={} reason={} userId={}",
                        pos.getSymbol(), reason, pos.getUserId());
            }
        }

        return blocking;
    }

    /**
     * Clear zero-price ghost positions
     */
    @Transactional
    public ClearGhostResult clearZeroPriceGhosts(UUID userId) {
        log.info("ghost.clear.requested userId={}", userId);

        List<PortfolioPosition> positions = portfolioPositionRepository
                .findByUserIdAndDeletedFalse(userId);

        int cleared = 0;
        List<String> clearedSymbols = new ArrayList<>();

        for (PortfolioPosition pos : positions) {
            // Only clear zero-price OR zero-quantity positions
            boolean isZeroPrice = pos.getAverageCost() == null || pos.getAverageCost().signum() == 0;
            boolean isZeroQty = pos.getQuantity() == null || pos.getQuantity().signum() == 0;

            if (isZeroPrice || isZeroQty) {
                pos.setDeleted(true);
                portfolioPositionRepository.save(pos);
                cleared++;
                clearedSymbols.add(pos.getSymbol());
                log.info("ghost.cleared symbol={} reason={} userId={}",
                        pos.getSymbol(),
                        isZeroPrice ? "ZERO_PRICE" : "ZERO_QTY",
                        userId);
            }
        }

        String message = cleared > 0 ?
                "Cleared " + cleared + " ghost positions: " + String.join(", ", clearedSymbols) :
                "No ghost positions found";

        log.info("ghost.clear.completed count={} userId={}", cleared, userId);
        return new ClearGhostResult(cleared, clearedSymbols, message, Instant.now());
    }

    /**
     * Force sync with broker and detect mismatches
     */
    @Transactional
    public SyncResult forceBrokerSync(UUID userId) {
        log.info("broker.sync.forced userId={}", userId);

        brokerPositionTruthService.syncUser(userId);
        BrokerPositionTruthSnapshot snapshot = brokerPositionTruthService.snapshot(userId);

        List<PortfolioPosition> omsPositions = portfolioPositionRepository
                .findByUserIdAndDeletedFalse(userId);

        List<GhostPosition> ghosts = findGhostPositions(omsPositions, snapshot);
        int mismatches = ghosts.size();

        String status = mismatches == 0 ? "OK" : "MISMATCHES_FOUND";
        String message = mismatches == 0 ?
                "All positions synced correctly with broker" :
                "Found " + mismatches + " position mismatches";

        log.info("broker.sync.completed userId={} status={} mismatches={}", userId, status, mismatches);
        return new SyncResult(
                snapshot.brokerConnected(),
                snapshot.lastSyncAt(),
                mismatches,
                ghosts,
                status,
                message
        );
    }

    /**
     * Reconcile specific position (OMS vs Broker)
     */
    @Transactional
    public PositionReconcileDetail reconcilePosition(UUID userId, String symbol) {
        log.info("position.reconcile requested symbol={} userId={}", symbol, userId);

        brokerPositionTruthService.syncUser(userId);
        BrokerPositionTruthSnapshot brokerSnapshot = brokerPositionTruthService.snapshot(userId);

        // Find OMS position
        PortfolioPosition omsPos = portfolioPositionRepository
                .findByUserIdAndDeletedFalse(userId).stream()
                .filter(p -> symbol.equalsIgnoreCase(p.getSymbol()))
                .findFirst()
                .orElse(null);

        // Find broker position
        BrokerPositionTruthSnapshot.BrokerTruthPositionRow brokerPos = null;
        if (brokerSnapshot.brokerConnected()) {
            for (var row : brokerSnapshot.positions()) {
                if (symbol.equalsIgnoreCase(row.symbol())) {
                    brokerPos = row;
                    break;
                }
            }
        }

        BigDecimal omsQty = omsPos != null ? omsPos.getQuantity() : BigDecimal.ZERO;
        BigDecimal brokerQty = brokerPos != null ? brokerPos.brokerQty() : BigDecimal.ZERO;
        boolean isMatched = omsQty.compareTo(brokerQty) == 0;

        String action = null;
        if (!isMatched && omsQty.signum() == 0) {
            action = "CLEAR_OMS_GHOST";
        } else if (!isMatched) {
            action = "MANUAL_REVIEW_REQUIRED";
        }

        log.info("position.reconcile completed symbol={} omsQty={} brokerQty={} matched={}",
                symbol, omsQty, brokerQty, isMatched);

        return new PositionReconcileDetail(
                symbol,
                omsQty,
                omsPos != null ? omsPos.getAverageCost() : null,
                brokerQty,
                brokerPos != null ? brokerPos.product() : null,
                isMatched,
                action,
                brokerSnapshot.lastSyncAt()
        );
    }

    /**
     * Scheduled reconciliation check (every hour)
     */
    @Scheduled(fixedDelayString = "${stokr.reconciliation.check-interval-ms:3600000}")
    @Transactional
    public void scheduledReconciliationCheck() {
        log.info("reconciliation.scheduled_check started");

        // In production, would iterate through all active traders
        // For now, log that check ran
        log.info("reconciliation.scheduled_check completed");
    }

    // DTOs for API responses

    public record PositionReconciliationStatus(
            int openOmsLegs,
            int ghostPositions,
            int blockingLive,
            int strategiesAtCapacity,
            String brokerConnected,
            String brokerSync,
            List<GhostPosition> ghosts,
            List<BlockingPosition> blocking,
            Instant checkedAt
    ) {
    }

    public record GhostPosition(
            String symbol,
            BigDecimal omsQuantity,
            BigDecimal brokerQuantity,
            BigDecimal averagePrice,
            UUID positionId,
            String description,
            String ghostType // ZERO_PRICE_GHOST, QUANTITY_MISMATCH, etc.
    ) {
    }

    public record BlockingPosition(
            String symbol,
            BigDecimal quantity,
            BigDecimal averagePrice,
            UUID positionId,
            String blockingReason,
            boolean preventsLiveEntry
    ) {
    }

    public record ClearGhostResult(
            int clearedCount,
            List<String> clearedSymbols,
            String message,
            Instant clearedAt
    ) {
    }

    public record SyncResult(
            boolean brokerConnected,
            Instant lastSyncAt,
            int mismatchCount,
            List<GhostPosition> mismatches,
            String status,
            String message
    ) {
    }

    public record PositionReconcileDetail(
            String symbol,
            BigDecimal omsQuantity,
            BigDecimal omsPrice,
            BigDecimal brokerQuantity,
            String brokerProduct,
            boolean matched,
            String suggestedAction,
            Instant lastSyncAt
    ) {
    }
}
