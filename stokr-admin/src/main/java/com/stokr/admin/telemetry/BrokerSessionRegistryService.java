package com.stokr.admin.telemetry;

import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.repository.BrokerAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Infrastructure view of broker OAuth sessions (not trader portfolio). Aggregates {@link BrokerAccount} rows.
 */
@Service
@RequiredArgsConstructor
public class BrokerSessionRegistryService {

    private static final List<String> VENDORS = List.of("ZERODHA", "DHAN", "UPSTOX", "ANGEL");

    private final BrokerAccountRepository brokerAccountRepository;

    public Map<String, Object> snapshot(Instant now) {
        Map<String, Object> vendors = new LinkedHashMap<>();
        for (String vendor : VENDORS) {
            vendors.put(vendor, vendorSnapshot(vendor, now));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", "BROKER_ACCOUNTS_DB");
        root.put("vendors", vendors);
        return root;
    }

    private Map<String, Object> vendorSnapshot(String vendor, Instant now) {
        List<BrokerAccount> rows = brokerAccountRepository.findAllByVendorCodeIgnoreCaseAndDeletedFalse(vendor);
        int connected = 0;
        int degraded = 0;
        int expired = 0;
        Instant latestHeartbeat = null;
        Instant nearestExpiry = null;
        for (BrokerAccount b : rows) {
            boolean isConn = b.getStatus() != null && "CONNECTED".equalsIgnoreCase(b.getStatus().trim());
            if (isConn) {
                connected++;
            }
            if (b.getTokenExpiresAt() != null) {
                nearestExpiry = nearestExpiry == null || b.getTokenExpiresAt().isBefore(nearestExpiry)
                        ? b.getTokenExpiresAt()
                        : nearestExpiry;
                if (b.getTokenExpiresAt().isBefore(now)) {
                    expired++;
                } else if (Duration.between(now, b.getTokenExpiresAt()).toMinutes() < 45) {
                    degraded++;
                }
            }
            if (b.getLastSyncAt() != null) {
                latestHeartbeat = latestHeartbeat == null || b.getLastSyncAt().isAfter(latestHeartbeat)
                        ? b.getLastSyncAt()
                        : latestHeartbeat;
            }
        }
        String status;
        if (rows.isEmpty()) {
            status = "DISCONNECTED";
        } else if (expired > 0 && connected == 0) {
            status = "DISCONNECTED";
        } else if (degraded > 0 || (nearestExpiry != null && Duration.between(now, nearestExpiry).toMinutes() < 45)) {
            status = "DEGRADED";
        } else if (connected > 0) {
            status = "CONNECTED";
        } else {
            status = "DISCONNECTED";
        }
        Long heartbeatAgeSec = latestHeartbeat == null ? null : Math.max(0L, Duration.between(latestHeartbeat, now).getSeconds());
        List<String> sampleUserIds = new ArrayList<>();
        int feedPausedAccounts = 0;
        for (BrokerAccount b : rows) {
            if (sampleUserIds.size() < 8) {
                sampleUserIds.add(b.getUserId().toString());
            }
            String meta = b.getMetadataJson();
            if (meta != null && meta.contains("\"opsFeedPaused\"") && meta.contains("true")) {
                feedPausedAccounts++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("accountRows", rows.size());
        m.put("connectedRows", connected);
        m.put("expiredTokens", expired);
        m.put("tokenExpiryNearest", nearestExpiry != null ? nearestExpiry.toString() : null);
        m.put("lastSyncAtMax", latestHeartbeat != null ? latestHeartbeat.toString() : null);
        m.put("heartbeatAgeSeconds", heartbeatAgeSec);
        m.put("websocketStatus", "UNKNOWN");
        m.put("reconnectCount", null);
        m.put("reconnectCountInstrumented", false);
        m.put("apiQuotaState", "UNKNOWN");
        m.put("feedLagSeconds", null);
        m.put("feedLagInstrumented", false);
        m.put("authStatus", expired > 0 ? "TOKENS_EXPIRED_PRESENT" : (connected > 0 ? "SESSIONS_CONNECTED" : "NO_ACTIVE_SESSIONS"));
        m.put("marketStreamHealth", rows.isEmpty() ? "NO_ACCOUNTS" : (connected > 0 ? "SESSION_OK" : "NO_CONNECTED_SESSIONS"));
        m.put("sampleUserIds", sampleUserIds);
        m.put("adminFeedPausedAccounts", feedPausedAccounts);
        m.put("note", "Vendor market-data websocket, reconnect counters, and API quota are not instrumented in-process ??? broker_accounts + last_sync_at are authoritative for this build.");
        return m;
    }

    public List<String> supportedVendors() {
        return new ArrayList<>(VENDORS);
    }
}
