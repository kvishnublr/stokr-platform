package com.stokr.admin.service;

import com.stokr.execution.reconciliation.PositionReconciliationService;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminSystemHealthFixService {

    private final PortfolioPositionRepository portfolioPositionRepository;
    private final OmsOrderRepository omsOrderRepository;
    private final PositionReconciliationService reconciliationService;

    /**
     * Execute ALL system health fixes
     */
    @Transactional
    public Map<String, Object> executeAllFixes(UUID userId) {
        Map<String, Object> results = new LinkedHashMap<>();

        log.info("admin_fix.starting userId={}", userId);

        // Fix 1: Clear zero-price ghosts
        Map<String, Object> ghostFix = clearZeroPriceGhosts(userId);
        results.put("cleared_zero_price_ghosts", ghostFix);

        // Fix 2: Clear stale positions (>24h not updated)
        Map<String, Object> staleFix = clearStalePositions(userId, 24);
        results.put("cleared_stale_positions", staleFix);

        // Fix 3: Expire stuck orders
        Map<String, Object> stuckOrdersFix = expireStuckOrders(userId, 5);
        results.put("expired_stuck_orders", stuckOrdersFix);

        // Fix 4: Clear zero-quantity orders
        Map<String, Object> zeroQtyFix = clearZeroQuantityOrders(userId);
        results.put("cleared_zero_qty_orders", zeroQtyFix);

        // Fix 5: Hard delete integrity failures
        Map<String, Object> integrityFix = deleteIntegrityFailures(userId);
        results.put("deleted_integrity_failures", integrityFix);

        // Fix 6: Reconcile position mismatches
        Map<String, Object> reconcileFix = reconcilePositionMismatches(userId);
        results.put("reconciled_positions", reconcileFix);

        results.put("completed_at", Instant.now().toString());
        results.put("userId", userId);

        log.info("admin_fix.completed userId={} results={}", userId, results);
        return results;
    }

    /**
     * Clear zero-price ghost positions
     */
    @Transactional
    private Map<String, Object> clearZeroPriceGhosts(UUID userId) {
        List<PortfolioPosition> positions = portfolioPositionRepository.findByUserIdAndDeletedFalse(userId);

        int cleared = 0;
        List<String> symbols = new ArrayList<>();

        for (PortfolioPosition pos : positions) {
            if ((pos.getAvgPrice() == null || pos.getAvgPrice().compareTo(BigDecimal.ZERO) == 0)) {
                pos.setDeleted(true);
                portfolioPositionRepository.save(pos);
                cleared++;
                symbols.add(pos.getSymbol());
                log.warn("admin_fix.zero_price_ghost_cleared symbol={} qty={} userId={}",
                        pos.getSymbol(), pos.getQuantity(), userId);
            }
        }

        return Map.of(
                "cleared_count", cleared,
                "symbols", symbols,
                "status", cleared > 0 ? "CLEARED" : "NO_GHOSTS_FOUND"
        );
    }

    /**
     * Clear stale positions (not updated for N hours)
     */
    @Transactional
    private Map<String, Object> clearStalePositions(UUID userId, int staleHours) {
        Instant staleBefore = Instant.now().minus(staleHours, ChronoUnit.HOURS);
        List<PortfolioPosition> positions = portfolioPositionRepository.findByUserIdAndDeletedFalse(userId);

        int cleared = 0;
        List<String> symbols = new ArrayList<>();

        for (PortfolioPosition pos : positions) {
            if (pos.getUpdatedAt() != null && pos.getUpdatedAt().isBefore(staleBefore)) {
                pos.setDeleted(true);
                portfolioPositionRepository.save(pos);
                cleared++;
                symbols.add(pos.getSymbol());
                log.warn("admin_fix.stale_position_cleared symbol={} lastUpdate={} userId={}",
                        pos.getSymbol(), pos.getUpdatedAt(), userId);
            }
        }

        return Map.of(
                "cleared_count", cleared,
                "symbols", symbols,
                "stale_hours_threshold", staleHours,
                "status", cleared > 0 ? "CLEARED" : "NO_STALE_POSITIONS"
        );
    }

    /**
     * Expire stuck orders (pending for > N minutes)
     */
    @Transactional
    private Map<String, Object> expireStuckOrders(UUID userId, int stuckMinutes) {
        Instant stuckBefore = Instant.now().minus(stuckMinutes, ChronoUnit.MINUTES);

        // Find orders stuck in pre-terminal states
        List<OmsOrder> stuckOrders = omsOrderRepository.findAll()
                .stream()
                .filter(o -> o.getUserId().equals(userId))
                .filter(o -> !o.isDeleted())
                .filter(o -> isPreTerminalState(o.getState()))
                .filter(o -> o.getCreatedAt() != null && o.getCreatedAt().isBefore(stuckBefore))
                .toList();

        int expired = 0;
        List<UUID> orderIds = new ArrayList<>();

        for (OmsOrder order : stuckOrders) {
            order.setState(OrderState.REJECTED);
            order.setRejectReason("ADMIN_FORCE_EXPIRE: Stuck for >" + stuckMinutes + "min");
            omsOrderRepository.save(order);
            expired++;
            orderIds.add(order.getId());
            log.warn("admin_fix.stuck_order_expired orderId={} state={} createdAt={} userId={}",
                    order.getId(), order.getState(), order.getCreatedAt(), userId);
        }

        return Map.of(
                "expired_count", expired,
                "order_ids", orderIds,
                "stuck_minutes_threshold", stuckMinutes,
                "status", expired > 0 ? "EXPIRED" : "NO_STUCK_ORDERS"
        );
    }

    /**
     * Clear zero-quantity orders (qty = 0 or null)
     */
    @Transactional
    private Map<String, Object> clearZeroQuantityOrders(UUID userId) {
        List<OmsOrder> allOrders = omsOrderRepository.findAll()
                .stream()
                .filter(o -> o.getUserId().equals(userId))
                .filter(o -> !o.isDeleted())
                .toList();

        int cleared = 0;
        List<UUID> orderIds = new ArrayList<>();

        for (OmsOrder order : allOrders) {
            if (order.getQuantity() == null || order.getQuantity().signum() == 0) {
                order.setDeleted(true);
                omsOrderRepository.save(order);
                cleared++;
                orderIds.add(order.getId());
                log.warn("admin_fix.zero_qty_order_cleared orderId={} state={} userId={}",
                        order.getId(), order.getState(), userId);
            }
        }

        return Map.of(
                "cleared_count", cleared,
                "order_ids", orderIds,
                "status", cleared > 0 ? "CLEARED" : "NO_ZERO_QTY_ORDERS"
        );
    }

    /**
     * Delete integrity failures (reject pending/created orders with high likelihood of failure)
     */
    @Transactional
    private Map<String, Object> deleteIntegrityFailures(UUID userId) {
        List<OmsOrder> allOrders = omsOrderRepository.findAll()
                .stream()
                .filter(o -> o.getUserId().equals(userId))
                .filter(o -> !o.isDeleted())
                .toList();

        int rejected = 0;
        List<UUID> orderIds = new ArrayList<>();

        for (OmsOrder order : allOrders) {
            // Mark for rejection: pending orders with missing critical fields
            if ((order.getState() == OrderState.PENDING_SUBMISSION ||
                 order.getState() == OrderState.CREATED) &&
                (order.getSymbol() == null || order.getSymbol().isBlank() ||
                 order.getQuantity() == null ||
                 order.getSide() == null || order.getSide().isBlank())) {

                order.setState(OrderState.REJECTED);
                order.setRejectReason("ADMIN_INTEGRITY_CLEANUP: Missing critical fields");
                omsOrderRepository.save(order);
                rejected++;
                orderIds.add(order.getId());
                log.warn("admin_fix.integrity_failure_rejected orderId={} symbol={} qty={} userId={}",
                        order.getId(), order.getSymbol(), order.getQuantity(), userId);
            }
        }

        return Map.of(
                "rejected_count", rejected,
                "order_ids", orderIds,
                "status", rejected > 0 ? "INTEGRITY_CLEANED" : "NO_INTEGRITY_ISSUES"
        );
    }

    /**
     * Reconcile position mismatches by syncing broker truth
     */
    @Transactional
    private Map<String, Object> reconcilePositionMismatches(UUID userId) {
        // Get reconciliation status to identify mismatches
        var status = reconciliationService.getReconciliationStatus(userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("open_oms_legs", status.openOmsLegs());
        result.put("ghost_count", status.ghostPositions());
        result.put("blocking_count", status.blockingLive());

        // Mark ghosts for cleanup
        if (!status.ghosts().isEmpty()) {
            List<UUID> ghostIds = status.ghosts().stream()
                    .map(g -> g.positionId())
                    .toList();

            for (UUID posId : ghostIds) {
                var pos = portfolioPositionRepository.findById(posId);
                if (pos.isPresent()) {
                    pos.get().setDeleted(true);
                    portfolioPositionRepository.save(pos.get());
                    log.warn("admin_fix.ghost_position_deleted posId={} userId={}", posId, userId);
                }
            }

            result.put("ghosts_deleted", ghostIds.size());
        }

        result.put("status", "RECONCILED");
        return result;
    }

    /**
     * Targeted cleanup for specific symbols (ghost positions blocking strategy)
     */
    @Transactional
    public Map<String, Object> cleanupGhostSymbols(UUID userId, List<String> symbols) {
        Map<String, Object> result = new LinkedHashMap<>();

        int ordersCleared = 0;
        int positionsCleared = 0;
        List<String> processedSymbols = new ArrayList<>();

        for (String symbol : symbols) {
            // Soft-delete all orders for this symbol
            List<OmsOrder> orders = omsOrderRepository.findAll()
                    .stream()
                    .filter(o -> o.getUserId().equals(userId))
                    .filter(o -> !o.isDeleted())
                    .filter(o -> symbol.equalsIgnoreCase(o.getSymbol()))
                    .toList();

            for (OmsOrder order : orders) {
                order.setDeleted(true);
                omsOrderRepository.save(order);
                ordersCleared++;
                log.warn("admin_fix.ghost_order_deleted symbol={} orderId={} userId={}", symbol, order.getId(), userId);
            }

            // Soft-delete all positions for this symbol
            List<PortfolioPosition> positions = portfolioPositionRepository.findByUserIdAndDeletedFalse(userId)
                    .stream()
                    .filter(p -> symbol.equalsIgnoreCase(p.getSymbol()))
                    .toList();

            for (PortfolioPosition pos : positions) {
                pos.setDeleted(true);
                portfolioPositionRepository.save(pos);
                positionsCleared++;
                log.warn("admin_fix.ghost_symbol_position_deleted symbol={} posId={} qty={} userId={}",
                        symbol, pos.getId(), pos.getQuantity(), userId);
            }

            if (!orders.isEmpty() || !positions.isEmpty()) {
                processedSymbols.add(symbol);
            }
        }

        result.put("symbols_processed", processedSymbols);
        result.put("orders_deleted", ordersCleared);
        result.put("positions_deleted", positionsCleared);
        result.put("status", ordersCleared > 0 || positionsCleared > 0 ? "CLEANED" : "NO_RECORDS_FOUND");
        result.put("completed_at", Instant.now().toString());

        log.info("admin_fix.ghost_symbols_cleanup userId={} symbols={} orders_deleted={} positions_deleted={}",
                userId, processedSymbols, ordersCleared, positionsCleared);

        return result;
    }

    private static boolean isPreTerminalState(OrderState state) {
        return state == OrderState.CREATED ||
               state == OrderState.VALIDATED ||
               state == OrderState.RISK_CHECK ||
               state == OrderState.PENDING_SUBMISSION ||
               state == OrderState.SUBMITTED ||
               state == OrderState.ACCEPTED ||
               state == OrderState.PARTIALLY_FILLED ||
               state == OrderState.CANCEL_REQUESTED ||
               state == OrderState.EXIT_REQUESTED;
    }
}
