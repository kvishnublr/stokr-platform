package com.stokr.admin.telemetry;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized market-plane snapshot (DB freshness + broker session hints). Vendor packet rates remain future work.
 */
@Service
@RequiredArgsConstructor
public class MarketPlaneMonitorService {

    private final MarketDataFreshnessService marketDataFreshnessService;
    private final BrokerSessionRegistryService brokerSessionRegistryService;

    public Map<String, Object> snapshot(Instant now) {
        Map<String, Object> fresh = marketDataFreshnessService.snapshot(now);
        Map<String, Object> brokers = brokerSessionRegistryService.snapshot(now);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("freshnessStatus", fresh.get("status"));
        m.put("latest1mLagSeconds", fresh.get("latest1mLagSeconds"));
        m.put("candles1mPerMinuteApprox", fresh.get("candles1mPerMinuteApprox"));
        m.put("distinctSymbols", fresh.get("distinctSymbols"));
        m.put("worstSymbols1m", fresh.get("worstSymbols1m"));
        m.put("brokerVendors", brokers.get("vendors"));
        m.put("adminFeedPausedAccountsApprox", countFeedPaused(brokers));
        m.put("collectedAt", now.toString());
        m.put("note", "Ownership of vendor sockets is inferred from broker_sessions + ops flags ??? not packet-level taps.");
        return m;
    }

    @SuppressWarnings("unchecked")
    private static int countFeedPaused(Map<String, Object> brokersRoot) {
        Object vendors = brokersRoot.get("vendors");
        if (!(vendors instanceof Map<?, ?> vm)) {
            return 0;
        }
        int n = 0;
        for (Object raw : vm.values()) {
            if (raw instanceof Map<?, ?> v) {
                Object c = v.get("adminFeedPausedAccounts");
                if (c instanceof Number num) {
                    n += num.intValue();
                }
            }
        }
        return n;
    }
}
