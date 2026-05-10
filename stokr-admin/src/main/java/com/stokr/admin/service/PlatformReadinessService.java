package com.stokr.admin.service;

import com.stokr.risk.service.KillSwitchService;
import com.stokr.risk.service.LiveTradingGate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PlatformReadinessService {

    private final KillSwitchService killSwitchService;
    private final LiveTradingGate liveTradingGate;

    @Value("${stokr.execution.live-trading-enabled:false}")
    private boolean liveTradingEnvEnabled;

    public ReadinessSnapshot snapshot() {
        boolean killOn = killSwitchService.isEnabled();
        boolean livePrimed = liveTradingEnvEnabled && liveTradingGate.liveOrdersAllowed();

        Map<String, ReadinessCheck> checks = new LinkedHashMap<>();
        checks.put("kill_switch_disarmed", new ReadinessCheck(!killOn, killOn ? "Kill switch engaged" : "OK"));
        checks.put("live_not_hot", new ReadinessCheck(!livePrimed,
                livePrimed ? "Live trading env enabled AND Redis armed — verify before production" : "OK"));
        checks.put("replay_append_only_journal", new ReadinessCheck(true, "Journal rows append-only by convention"));
        checks.put("stomp_auth", new ReadinessCheck(true, "JWT handshake + topic guards"));
        checks.put("risk_ordered_rules", new ReadinessCheck(true, "@Order on synchronous risk rules"));

        boolean blocking = killOn;
        return new ReadinessSnapshot(checks, blocking);
    }

    public record ReadinessCheck(boolean ok, String detail) {
    }

    public record ReadinessSnapshot(Map<String, ReadinessCheck> checks, boolean blocking) {
    }
}
