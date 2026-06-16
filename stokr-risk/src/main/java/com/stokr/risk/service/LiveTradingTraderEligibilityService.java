package com.stokr.risk.service;

import com.stokr.common.simulation.SimulationModeService;
import com.stokr.common.simulation.SimulationScenarioContext;
import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.risk.model.LiveTraderEligibilityResult;
import com.stokr.strategy.domain.StrategyDefinition;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.repository.BrokerAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Central guard for routing real-broker (LIVE) work: trader onboarding, broker health, strategy catalog, platform arms.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LiveTradingTraderEligibilityService {

    private final KillSwitchService killSwitchService;
    private final LiveTradingGate liveTradingGate;
    private final AuthUserRepository authUserRepository;
    private final BrokerAccountRepository brokerAccountRepository;
    private final StrategyDefinitionRepository strategyDefinitionRepository;
    private final StrategyInstanceRepository strategyInstanceRepository;
    private final ObjectMapper objectMapper;
    private final SimulationModeService simulationModeService;

    @Value("${stokr.runtime.live-heartbeat-stale-seconds:600}")
    private long liveHeartbeatStaleSeconds;

    @Value("${stokr.strategy.system-user-id:33333333-3333-3333-3333-333333333333}")
    private UUID systemUserId;

    @Value("${stokr.strategy.primary-trader-user-id:}")
    private String primaryTraderUserIdRaw;

    /**
     * Paper / SIM path: strategy must exist in catalog and be enabled.
     */
    public LiveTraderEligibilityResult evaluateForPaperTrading(String strategyKey) {
        return strategyGate(strategyKey);
    }

    /**
     * LIVE order routing ??? includes healthy LIVE runtime heartbeat for the strategy key.
     */
    public LiveTraderEligibilityResult evaluateForLiveOrder(UUID userId, String strategyKey, String brokerVendor) {
        return evaluateLiveCore(userId, strategyKey, brokerVendor, true);
    }

    /**
     * Starting a LIVE runtime ??? broker/trader gates without requiring an already-running heartbeat.
     */
    public LiveTraderEligibilityResult evaluateForLiveStrategyActivation(UUID userId, String strategyKey, String brokerVendor) {
        return evaluateLiveCore(userId, strategyKey, brokerVendor, false);
    }

    /**
     * Catalog auto-trading path for system-generated signals: platform gates plus primary trader broker health.
     * Does not require the synthetic system user to exist in auth_users or hold broker credentials.
     */
    public LiveTraderEligibilityResult evaluateForCatalogSystemLive(String strategyKey, String brokerVendor) {
        if (SimulationScenarioContext.active() && simulationModeService.isActive()) {
            return LiveTraderEligibilityResult.ok();
        }
        if (killSwitchService.isEnabled()) {
            return LiveTraderEligibilityResult.reject("KILL_SWITCH", "Global kill switch is enabled");
        }
        if (!liveTradingGate.liveOrdersAllowed()) {
            return LiveTraderEligibilityResult.reject(
                    "LIVE_TRADING_BLOCKED",
                    "Live trading disabled or platform not armed"
            );
        }
        LiveTraderEligibilityResult strat = strategyGate(strategyKey);
        if (!strat.allowed()) {
            return strat;
        }
        UUID primaryTrader = parsePrimaryTraderUserId();
        if (primaryTrader == null) {
            return LiveTraderEligibilityResult.reject(
                    "PRIMARY_TRADER_NOT_CONFIGURED",
                    "Configure stokr.strategy.primary-trader-user-id for catalog LIVE execution"
            );
        }
        return evaluateLiveCore(primaryTrader, strategyKey, brokerVendor, false);
    }

    public boolean isSystemUser(UUID userId) {
        return userId != null && Objects.equals(systemUserId, userId);
    }

    private LiveTraderEligibilityResult evaluateLiveCore(
            UUID userId,
            String strategyKey,
            String brokerVendor,
            boolean requireHealthyLiveRuntime
    ) {
        if (SimulationScenarioContext.active() && simulationModeService.isActive()) {
            return LiveTraderEligibilityResult.ok();
        }
        if (killSwitchService.isEnabled()) {
            return LiveTraderEligibilityResult.reject("KILL_SWITCH", "Global kill switch is enabled");
        }
        if (!liveTradingGate.liveOrdersAllowed()) {
            return LiveTraderEligibilityResult.reject(
                    "LIVE_TRADING_BLOCKED",
                    "Live trading disabled or platform not armed"
            );
        }
        LiveTraderEligibilityResult strat = strategyGate(strategyKey);
        if (!strat.allowed()) {
            return strat;
        }

        Optional<AuthUser> userOpt = authUserRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return LiveTraderEligibilityResult.reject("USER_NOT_FOUND", "Trader account not found");
        }
        AuthUser u = userOpt.get();
        if (!u.isEnabled() || u.isDeleted()) {
            return LiveTraderEligibilityResult.reject("USER_DISABLED", "Trader account is disabled");
        }
        if (!u.isEmailVerified()) {
            return LiveTraderEligibilityResult.reject("EMAIL_NOT_VERIFIED", "Email not verified");
        }
        if (!u.isOnboardingComplete()) {
            return LiveTraderEligibilityResult.reject("ONBOARDING_INCOMPLETE", "Trader onboarding not complete");
        }
        if (!u.isTelegramVerified()) {
            return LiveTraderEligibilityResult.reject("TELEGRAM_NOT_VERIFIED", "Telegram not verified");
        }
        if (!u.isLiveTradingApproved()) {
            return LiveTraderEligibilityResult.reject("LIVE_NOT_APPROVED", "Live trading not approved by operations");
        }

        String vendor = brokerVendor != null && !brokerVendor.isBlank() ? brokerVendor.trim() : "ZERODHA";
        Optional<BrokerAccount> brokerOpt =
                brokerAccountRepository.findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(
                        userId,
                        vendor
                );
        if (brokerOpt.isEmpty()) {
            return LiveTraderEligibilityResult.reject(
                    "BROKER_NOT_CONNECTED",
                    "No connected broker account for " + vendor
            );
        }
        BrokerAccount broker = brokerOpt.get();
        if (!brokerStatusReady(broker.getStatus())) {
            return LiveTraderEligibilityResult.reject(
                    "BROKER_NOT_READY",
                    "Broker account is not in a connected state"
            );
        }
        String hs = broker.getHealthStatus();
        if (hs != null && hs.equalsIgnoreCase("DISCONNECTED")) {
            return LiveTraderEligibilityResult.reject("BROKER_UNHEALTHY", "Broker connection is disconnected");
        }
        if (!brokerAccessValid(broker)) {
            return LiveTraderEligibilityResult.reject("BROKER_TOKEN_EXPIRED", "Broker access token missing or expired");
        }

        if (requireHealthyLiveRuntime) {
            Instant cutoff = Instant.now().minusSeconds(Math.max(60L, liveHeartbeatStaleSeconds));
            if (!strategyInstanceRepository.existsLiveHealthyRuntime(userId, strategyKey.trim(), cutoff)) {
                return LiveTraderEligibilityResult.reject(
                        "RUNTIME_UNHEALTHY",
                        "No healthy LIVE strategy runtime (heartbeat) for this strategy"
                );
            }
        }

        return LiveTraderEligibilityResult.ok();
    }

    private boolean brokerAccessValid(BrokerAccount broker) {
        if (broker.getAccessTokenEnc() != null && !broker.getAccessTokenEnc().isBlank()) {
            if (broker.getTokenExpiresAt() != null && broker.getTokenExpiresAt().isBefore(Instant.now())) {
                return false;
            }
            return true;
        }
        return accessTokenValid(broker.getMetadataJson());
    }

    private LiveTraderEligibilityResult strategyGate(String strategyKey) {
        if (strategyKey == null || strategyKey.isBlank()) {
            return LiveTraderEligibilityResult.reject("STRATEGY_KEY_MISSING", "Strategy key is required");
        }
        Optional<StrategyDefinition> def = strategyDefinitionRepository.findByStrategyKeyAndDeletedFalse(strategyKey.trim());
        if (def.isEmpty()) {
            return LiveTraderEligibilityResult.reject("STRATEGY_UNKNOWN", "Strategy is not available");
        }
        if (!def.get().isEnabled()) {
            return LiveTraderEligibilityResult.reject("STRATEGY_DISABLED", "Strategy is disabled");
        }
        return LiveTraderEligibilityResult.ok();
    }

    private static boolean brokerStatusReady(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return switch (status.trim().toUpperCase()) {
            case "CONNECTED", "ACTIVE", "LINKED", "OK" -> true;
            default -> false;
        };
    }

    /**
     * metadata_json may contain accessTokenExpiresAt (epoch millis) or expiresAt (millis or ISO).
     */
    private boolean accessTokenValid(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return false;
        }
        try {
            JsonNode n = objectMapper.readTree(metadataJson);
            if (n.hasNonNull("accessTokenExpiresAt")) {
                long expMs = n.get("accessTokenExpiresAt").asLong();
                return Instant.now().toEpochMilli() < expMs;
            }
            if (n.hasNonNull("expiresAt")) {
                if (n.get("expiresAt").isNumber()) {
                    long expMs = n.get("expiresAt").asLong();
                    return expMs > 1_000_000_000_000L
                            ? Instant.now().toEpochMilli() < expMs
                            : Instant.now().getEpochSecond() < expMs;
                }
                String iso = n.get("expiresAt").asText();
                return Instant.parse(iso).isAfter(Instant.now());
            }
            // Present metadata but no expiry ??? treat as valid until refresh writes expiry.
            return true;
        } catch (Exception e) {
            log.debug("broker.metadata.parse_failed {}", e.toString());
            return false;
        }
    }

    private UUID parsePrimaryTraderUserId() {
        if (primaryTraderUserIdRaw == null || primaryTraderUserIdRaw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(primaryTraderUserIdRaw.trim());
        } catch (IllegalArgumentException ex) {
            log.warn("live.eligibility.invalid_primary_trader_user_id value={}", primaryTraderUserIdRaw);
            return null;
        }
    }
}
