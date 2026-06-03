package com.stokr.execution.safety;

import com.stokr.risk.service.BrokerOperationalCircuitService;
import com.stokr.user.broker.PlatformMarketFeedService;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.repository.BrokerAccountRepository;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrokerDisconnectProtectionService {

    private static final String VENDOR = "ZERODHA";

    private final BrokerAccountRepository brokerAccountRepository;
    private final PlatformBrokerFeedSessionRepository feedSessionRepository;
    private final BrokerOperationalCircuitService brokerOperationalCircuitService;
    private final TradingKillSwitchService killSwitchService;
    private final PlatformMarketFeedService platformMarketFeedService;

    @Value("${stokr.oms.broker-disconnect.block-live:true}")
    private boolean blockLive;

    @Value("${stokr.oms.broker-disconnect.flatten-on-disconnect:false}")
    private boolean flattenOnDisconnect;

    @Value("${stokr.oms.broker-disconnect.halt-ttl-hours:4}")
    private long haltTtlHours;

    @Value("${stokr.oms.broker-disconnect.auto-heal-before-block:true}")
    private boolean autoHealBeforeBlock;

    @Value("${stokr.strategy.primary-trader-user-id:}")
    private String primaryTraderUserIdRaw;

    public boolean isExecutionDegraded(UUID userId) {
        return isGlobalBrokerDegraded() || isTraderBrokerDegraded(resolveExecutionUserId(userId));
    }

    public boolean isGlobalBrokerDegraded() {
        if (brokerOperationalCircuitService.isGlobalBrokerHalt()) {
            return true;
        }
        return feedSessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse(VENDOR)
                .map(s -> {
                    String state = s.getWebsocketState();
                    return state == null || !state.toUpperCase().contains("OPEN");
                })
                .orElse(true);
    }

    /**
     * Trader OAuth row used for LIVE order placement (distinct from admin platform feed session).
     */
    public boolean isTraderBrokerDegraded(UUID userId) {
        UUID execUser = resolveExecutionUserId(userId);
        if (execUser == null) {
            return true;
        }
        Optional<BrokerAccount> accountOpt = findZerodhaAccount(execUser);
        if (accountOpt.isEmpty()) {
            return true;
        }
        return isAccountUnhealthy(accountOpt.get());
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
        if (!blockLive) {
            return false;
        }
        UUID execUser = resolveExecutionUserId(userId);
        if (autoHealBeforeBlock && execUser != null && !isGlobalBrokerDegraded()) {
            attemptTraderBrokerHeal(execUser);
        }
        return isExecutionDegraded(userId);
    }

    public Map<String, Object> snapshot(UUID userId) {
        UUID execUser = resolveExecutionUserId(userId);
        if (autoHealBeforeBlock && execUser != null && !isGlobalBrokerDegraded()) {
            attemptTraderBrokerHeal(execUser);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("globalHalt", brokerOperationalCircuitService.isGlobalBrokerHalt());
        m.put("platformFeedDegraded", isGlobalBrokerDegraded());
        m.put("requestedUserId", userId != null ? userId.toString() : null);
        m.put("executionUserId", execUser != null ? execUser.toString() : null);
        m.put("traderBrokerDegraded", isTraderBrokerDegraded(userId));
        m.put("blockLiveOnDisconnectEnabled", blockLive);
        m.put("liveOrdersBlocked", blocksLiveOrders(userId));
        m.put("flattenOnDisconnect", flattenOnDisconnect);
        findZerodhaAccount(execUser).ifPresent(a -> {
            m.put("traderBrokerStatus", a.getStatus());
            m.put("traderBrokerHealth", a.getHealthStatus());
            m.put("traderTokenExpiresAt", a.getTokenExpiresAt());
        });
        feedSessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse(VENDOR).ifPresent(s -> {
            m.put("websocketState", s.getWebsocketState());
            m.put("lastTickAt", s.getLastTickAt());
        });
        return m;
    }

    /**
     * Admin diagnostics often pass the logged-in admin UUID (no broker_accounts row).
     * LIVE catalog execution uses the configured primary trader — align checks with that user.
     */
    public UUID resolveExecutionUserId(UUID userId) {
        if (userId != null && findZerodhaAccount(userId).isPresent()) {
            return userId;
        }
        UUID primary = parsePrimaryTraderUserId();
        if (primary != null) {
            return primary;
        }
        return userId;
    }

    private void attemptTraderBrokerHeal(UUID execUser) {
        try {
            platformMarketFeedService.syncPlatformTokensToTraders();
            findZerodhaAccount(execUser).ifPresent(account ->
                    platformMarketFeedService.ensureValidTraderZerodhaToken(account, Duration.ofMinutes(30)));
        } catch (Exception ex) {
            log.debug("oms.broker.auto_heal_skipped userId={} {}", execUser, ex.toString());
        }
    }

    private Optional<BrokerAccount> findZerodhaAccount(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return brokerAccountRepository.findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(
                userId, VENDOR);
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
        if (health != null && health.trim().equalsIgnoreCase("DISCONNECTED")) {
            return true;
        }
        if (health != null && health.trim().equalsIgnoreCase("DEGRADED")) {
            return !hasUsableAccessToken(account);
        }
        return false;
    }

    private static boolean hasUsableAccessToken(BrokerAccount account) {
        if (account.getAccessTokenEnc() == null || account.getAccessTokenEnc().isBlank()) {
            return false;
        }
        Instant expiresAt = account.getTokenExpiresAt();
        return expiresAt == null || expiresAt.isAfter(Instant.now());
    }

    private UUID parsePrimaryTraderUserId() {
        if (primaryTraderUserIdRaw == null || primaryTraderUserIdRaw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(primaryTraderUserIdRaw.trim());
        } catch (IllegalArgumentException ex) {
            log.warn("oms.broker.invalid_primary_trader_user_id value={}", primaryTraderUserIdRaw);
            return null;
        }
    }
}
