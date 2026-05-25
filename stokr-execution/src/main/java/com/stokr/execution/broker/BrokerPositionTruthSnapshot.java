package com.stokr.execution.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record BrokerPositionTruthSnapshot(
        BrokerPositionTruthSyncState syncState,
        Instant lastSyncAt,
        long syncLatencyMs,
        boolean brokerConnected,
        List<BrokerTruthPositionRow> positions,
        List<BrokerTruthMismatch> mismatches,
        Set<String> brokerClosedSymbols,
        Set<String> blockedSymbols,
        int pendingBrokerOrders,
        String message
) {
    public record BrokerTruthPositionRow(
            String symbol,
            BigDecimal brokerQty,
            BigDecimal internalQty,
            BigDecimal brokerAvgPrice,
            BigDecimal brokerRealizedPnl,
            BigDecimal brokerUnrealizedPnl,
            String product,
            String rowSyncState
    ) {
    }

    public record BrokerTruthMismatch(
            String symbol,
            String kind,
            BigDecimal brokerQty,
            BigDecimal internalQty,
            Instant detectedAt
    ) {
    }

    public static BrokerPositionTruthSnapshot empty(boolean connected) {
        return new BrokerPositionTruthSnapshot(
                connected ? BrokerPositionTruthSyncState.PENDING_SYNC : BrokerPositionTruthSyncState.STALE,
                null,
                0L,
                connected,
                List.of(),
                List.of(),
                Set.of(),
                Set.of(),
                0,
                connected ? "Awaiting first broker sync" : "Broker not connected"
        );
    }

    public Map<String, Object> toApiMap() {
        return Map.of(
                "syncState", syncState.name(),
                "lastSyncAt", lastSyncAt != null ? lastSyncAt.toString() : null,
                "syncLatencyMs", syncLatencyMs,
                "brokerConnected", brokerConnected,
                "positions", positions.stream().map(p -> Map.of(
                        "symbol", p.symbol(),
                        "brokerQty", p.brokerQty(),
                        "internalQty", p.internalQty(),
                        "brokerAvgPrice", p.brokerAvgPrice(),
                        "brokerRealizedPnl", p.brokerRealizedPnl(),
                        "brokerUnrealizedPnl", p.brokerUnrealizedPnl(),
                        "product", p.product() != null ? p.product() : "",
                        "rowSyncState", p.rowSyncState()
                )).toList(),
                "mismatches", mismatches.stream().map(m -> Map.of(
                        "symbol", m.symbol(),
                        "kind", m.kind(),
                        "brokerQty", m.brokerQty(),
                        "internalQty", m.internalQty(),
                        "detectedAt", m.detectedAt() != null ? m.detectedAt().toString() : null
                )).toList(),
                "brokerClosedSymbols", brokerClosedSymbols,
                "blockedSymbols", blockedSymbols,
                "pendingBrokerOrders", pendingBrokerOrders,
                "message", message != null ? message : ""
        );
    }
}
