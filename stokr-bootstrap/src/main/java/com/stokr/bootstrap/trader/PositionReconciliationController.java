package com.stokr.bootstrap.trader;

import com.stokr.execution.reconciliation.PositionReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/trader/positions")
@RequiredArgsConstructor
public class PositionReconciliationController {

    private final PositionReconciliationService reconciliationService;

    /**
     * Get position reconciliation status
     * Shows ghosts, blocking positions, sync status
     */
    @GetMapping("/reconciliation")
    public Map<String, Object> getReconciliationStatus(
            @RequestHeader(value = "X-User-Id") UUID userId) {

        var status = reconciliationService.getReconciliationStatus(userId);

        return Map.of(
                "userId", userId.toString(),
                "summary", Map.of(
                        "openOmsLegs", status.openOmsLegs(),
                        "ghostPositions", status.ghostPositions(),
                        "blockingLive", status.blockingLive(),
                        "strategiesAtCapacity", status.strategiesAtCapacity()
                ),
                "broker", Map.of(
                        "connected", status.brokerConnected(),
                        "syncStatus", String.valueOf(status.brokerSync()),
                        "lastSync", status.checkedAt().toString()
                ),
                "ghosts", status.ghosts().stream()
                        .map(g -> Map.of(
                                "symbol", g.symbol(),
                                "omsQty", g.omsQuantity().toPlainString(),
                                "brokerQty", g.brokerQuantity().toPlainString(),
                                "avgPrice", g.averagePrice() != null ? g.averagePrice().toPlainString() : "0",
                                "type", g.ghostType(),
                                "description", g.description()
                        ))
                        .toList(),
                "blocking", status.blocking().stream()
                        .map(b -> Map.of(
                                "symbol", b.symbol(),
                                "qty", b.quantity().toPlainString(),
                                "reason", b.blockingReason(),
                                "preventsLive", b.preventsLiveEntry()
                        ))
                        .toList(),
                "checkedAt", status.checkedAt().toString()
        );
    }

    /**
     * Clear zero-price ghost positions
     * Action: Remove ghosts blocking LIVE entries
     */
    @PostMapping("/clear-ghosts")
    public Map<String, Object> clearGhostPositions(
            @RequestHeader(value = "X-User-Id") UUID userId) {

        var result = reconciliationService.clearZeroPriceGhosts(userId);

        return Map.of(
                "action", "CLEAR_GHOST_POSITIONS",
                "userId", userId.toString(),
                "cleared", result.clearedCount(),
                "symbols", result.clearedSymbols(),
                "message", result.message(),
                "clearedAt", result.clearedAt().toString(),
                "nextAction", result.clearedCount() > 0 ? "Force broker sync" : "No action needed"
        );
    }

    /**
     * Force broker position sync
     * Action: Refresh positions from ZERODHA and detect mismatches
     */
    @PostMapping("/force-sync")
    public Map<String, Object> forceBrokerSync(
            @RequestHeader(value = "X-User-Id") UUID userId) {

        var result = reconciliationService.forceBrokerSync(userId);

        return Map.of(
                "action", "FORCE_BROKER_SYNC",
                "userId", userId.toString(),
                "brokerConnected", result.brokerConnected(),
                "lastSync", result.lastSyncAt() != null ? result.lastSyncAt().toString() : "NEVER",
                "status", result.status(),
                "message", result.message(),
                "mismatchCount", result.mismatchCount(),
                "mismatches", result.mismatches().stream()
                        .map(m -> Map.of(
                                "symbol", m.symbol(),
                                "omsQty", m.omsQuantity().toPlainString(),
                                "brokerQty", m.brokerQuantity().toPlainString()
                        ))
                        .toList()
        );
    }

    /**
     * Reconcile specific position
     * Returns detailed OMS vs Broker comparison
     */
    @GetMapping("/reconcile/{symbol}")
    public Map<String, Object> reconcilePosition(
            @RequestHeader(value = "X-User-Id") UUID userId,
            @PathVariable String symbol) {

        var detail = reconciliationService.reconcilePosition(userId, symbol);

        return Map.of(
                "symbol", detail.symbol(),
                "oms", Map.of(
                        "quantity", detail.omsQuantity().toPlainString(),
                        "avgPrice", detail.omsPrice() != null ? detail.omsPrice().toPlainString() : "0"
                ),
                "broker", Map.of(
                        "quantity", detail.brokerQuantity().toPlainString(),
                        "product", detail.brokerProduct() != null ? detail.brokerProduct() : "N/A"
                ),
                "reconciliation", Map.of(
                        "matched", detail.matched(),
                        "suggestedAction", detail.suggestedAction() != null ? detail.suggestedAction() : "NO_ACTION",
                        "lastSyncAt", detail.lastSyncAt() != null ? detail.lastSyncAt().toString() : "NEVER"
                ),
                "recommendation", detail.matched() ? "✓ Position is reconciled" :
                        detail.suggestedAction() != null ? detail.suggestedAction() : "Manual review required"
        );
    }

    /**
     * Get detailed position reconciliation report
     * For admin/support debugging
     */
    @GetMapping("/report")
    public Map<String, Object> getReconciliationReport(
            @RequestHeader(value = "X-User-Id") UUID userId) {

        var status = reconciliationService.getReconciliationStatus(userId);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("timestamp", System.currentTimeMillis());
        result.put("userId", userId.toString());
        result.put("summary", Map.of(
                "openLegs", status.openOmsLegs(),
                "ghosts", status.ghostPositions(),
                "blocking", status.blockingLive(),
                "healthScore", calculateHealthScore(status)
        ));

        Map<String, Object> brokerMap = new java.util.LinkedHashMap<>();
        brokerMap.put("status", status.brokerConnected());
        brokerMap.put("sync", String.valueOf(status.brokerSync()));
        result.put("broker", brokerMap);

        Map<String, Object> detailsMap = new java.util.LinkedHashMap<>();
        detailsMap.put("ghosts", status.ghosts());
        detailsMap.put("blocking", status.blocking());
        result.put("details", detailsMap);

        Map<String, Object> actionsMap = new java.util.LinkedHashMap<>();
        actionsMap.put("clearGhosts", status.ghostPositions() > 0 ? "RECOMMENDED" : "NOT_NEEDED");
        actionsMap.put("syncBroker", status.blockingLive() > 0 ? "RECOMMENDED" : "CURRENT");
        actionsMap.put("manualReview", status.ghostPositions() > 2 ? "URGENT" : "OPTIONAL");
        result.put("actions", actionsMap);

        return result;
    }

    private String calculateHealthScore(PositionReconciliationService.PositionReconciliationStatus status) {
        int issues = status.ghostPositions() + status.blockingLive() + status.strategiesAtCapacity();
        if (issues == 0) return "HEALTHY ✓";
        if (issues <= 2) return "WARNING ⚠️";
        return "CRITICAL 🔴";
    }
}
