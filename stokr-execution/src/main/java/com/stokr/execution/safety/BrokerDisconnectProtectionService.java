package com.stokr.execution.safety;

import com.stokr.risk.service.BrokerOperationalCircuitService;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.repository.BrokerAccountRepository;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrokerDisconnectProtectionService {

    private final BrokerAccountRepository brokerAccountRepository;
    private final PlatformBrokerFeedSessionRepository feedSessionRepository;
    private final BrokerOperationalCircuitService brokerOperationalCircuitService;
    private final TradingKillSwitchService killSwitchService;

    @Value("${stokr.oms.broker-disconnect.block-live:true}")
    private boolean blockLive;

    @Value("${stokr.oms.broker-disconnect.flatten-on-disconnect:false}")
    private boolean flattenOnDisconnect;

    @Value("${stokr.oms.broker-disconnect.halt-ttl-hours:4}")
    private long haltTtlHours;

    public boolean isExecutionDegraded(UUID userId) {
        return isGlobalBrokerDegraded() || isTraderBrokerDegraded(userId);
    }

    public boolean isGlobalBrokerDegraded() {
        if (brokerOperationalCircuitService.isGlobalBrokerHalt()) {
            return true;
        }
        return feedSessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse("ZERODHA")
                .map(s -> {
                    String state = s.getWebsocketState();
                    return state == null || !state.toUpperCase().contains("OPEN");
                })
                .orElse(true);
    }

    public boolean isTraderBrokerDegraded(UUID userId) {
        if (userId == null) {
            return false;
        }
        return brokerAccountRepository.findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, "ZERODHA")
                .map(this::isAccountUnhealthy)
                .orElse(true);
    }

    public void onBrokerDisconnected(String reason) {
        log.error("oms.broker.disconnect reason={}", reason);
        brokerOperationalCircuitService.haltGlobally(reason, Duration.ofHours(haltTtlHours));
        if (flattenOnDisconnect) {
            killSwitchService.activate(
                    TradingKillSwitchService.TriggerSource.BROKER_DISCONNECT,
                    reason,
                    true,
                    "broker-monitor");
        }
    }

    public void onBrokerRecovered() {
        brokerOperationalCircuitService.clearGlobalHalt();
        log.info("oms.broker.recovered global halt cleared");
    }

    public boolean blocksLiveOrders(UUID userId) {
        return blockLive && isExecutionDegraded(userId);
    }

    public Map<String, Object> snapshot(UUID userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("globalHalt", brokerOperationalCircuitService.isGlobalBrokerHalt());
        m.put("platformFeedDegraded", isGlobalBrokerDegraded());
        m.put("traderBrokerDegraded", isTraderBrokerDegraded(userId));
        m.put("blockLive", blockLive);
        m.put("flattenOnDisconnect", flattenOnDisconnect);
        feedSessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse("ZERODHA").ifPresent(s -> {
            m.put("websocketState", s.getWebsocketState());
            m.put("lastTickAt", s.getLastTickAt());
        });
        return m;
    }

    private boolean isAccountUnhealthy(BrokerAccount account) {
        if (account.getStatus() == null) {
            return true;
        }
        String status = account.getStatus().trim().toUpperCase();
        if (!status.equals("CONNECTED") && !status.equals("ACTIVE")
                && !status.equals("LINKED") && !status.equals("OK")) {
            return true;
        }
        String health = account.getHealthStatus();
        return health != null && health.trim().equalsIgnoreCase("DISCONNECTED");
    }
}
