package com.stokr.execution.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("syncState", syncState != null ? syncState.name() : "STALE");
        out.put("lastSyncAt", lastSyncAt != null ? lastSyncAt.toString() : null);
        out.put("syncLatencyMs", syncLatencyMs);
        out.put("brokerConnected", brokerConnected);
        out.put("positions", positions.stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", p.symbol());
            row.put("brokerQty", p.brokerQty());
            row.put("internalQty", p.internalQty());
            row.put("brokerAvgPrice", p.brokerAvgPrice());
            row.put("brokerRealizedPnl", p.brokerRealizedPnl());
            row.put("brokerUnrealizedPnl", p.brokerUnrealizedPnl());
            row.put("product", p.product() != null ? p.product() : "");
            row.put("rowSyncState", p.rowSyncState() != null ? p.rowSyncState() : "");
            return row;
        }).toList());
        out.put("mismatches", mismatches.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", m.symbol());
            row.put("kind", m.kind());
            row.put("brokerQty", m.brokerQty());
            row.put("internalQty", m.internalQty());
            row.put("detectedAt", m.detectedAt() != null ? m.detectedAt().toString() : null);
            return row;
        }).toList());
        out.put("brokerClosedSymbols", brokerClosedSymbols);
        out.put("blockedSymbols", blockedSymbols);
        out.put("pendingBrokerOrders", pendingBrokerOrders);
        out.put("message", message != null ? message : "");

        BigDecimal totalRealized = BigDecimal.ZERO;
        BigDecimal totalUnrealized = BigDecimal.ZERO;
        int openPositionCount = 0;
        for (BrokerTruthPositionRow p : positions) {
            totalRealized = totalRealized.add(nullSafe(p.brokerRealizedPnl()));
            totalUnrealized = totalUnrealized.add(nullSafe(p.brokerUnrealizedPnl()));
            if (p.brokerQty() != null && p.brokerQty().compareTo(BigDecimal.ZERO) != 0) {
                openPositionCount++;
            }
        }
        BigDecimal totalMtm = totalRealized.add(totalUnrealized);
        out.put("totalRealizedPnl", totalRealized);
        out.put("totalUnrealizedPnl", totalUnrealized);
        out.put("totalMtmPnl", totalMtm);
        out.put("openPositionCount", openPositionCount);
        return out;
    }

    private static BigDecimal nullSafe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
