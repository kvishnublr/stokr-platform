package com.stokr.bootstrap.operational;

import com.stokr.marketdata.monitor.FeedHealthMonitorService;
import com.stokr.strategy.runtime.SignalPipelineActivationService;
import com.stokr.user.broker.PlatformMarketFeedService;
import com.stokr.user.domain.PlatformBrokerFeedSession;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps platform feed, tokens, websocket, and signal pipeline self-healing without admin clicks.
 * Stops when OAuth re-auth is required (no refresh token / AUTH_EXPIRED).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformOperationalAutoHealScheduler {

    private static final String VENDOR = "ZERODHA";

    private final PlatformMarketFeedService platformMarketFeedService;
    private final PlatformBrokerFeedSessionRepository sessionRepository;
    private final FeedHealthMonitorService feedHealthMonitorService;
    private final SignalPipelineActivationService signalPipelineActivationService;

    private final AtomicLong lastReconnectRequestMillis = new AtomicLong(0);
    private final AtomicLong lastPipelineActivateMillis = new AtomicLong(0);

    @Value("${stokr.platform.auto-heal.enabled:true}")
    private boolean enabled;

    @Value("${stokr.platform.auto-heal.reconnect-cooldown-sec:30}")
    private long reconnectCooldownSec;

    @Value("${stokr.platform.auto-heal.pipeline-cooldown-sec:300}")
    private long pipelineCooldownSec;

    @Scheduled(fixedDelayString = "${stokr.platform.auto-heal.interval-ms:45000}")
    public void healOperationalLayers() {
        if (!enabled) {
            return;
        }
        try {
            platformMarketFeedService.ensureSessionFromTraderFallback(VENDOR);
            Map<String, Object> tokenSummary = platformMarketFeedService.refreshAllZerodhaTokens(Duration.ofMinutes(30));

            PlatformBrokerFeedSession session = sessionRepository
                    .findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(VENDOR)
                    .orElse(null);
            if (session == null) {
                log.debug("platform.auto_heal skip reason=no_session");
                return;
            }
            if (requiresUserOAuth(session)) {
                log.debug("platform.auto_heal skip reason=user_oauth_required state={}", session.getConnectionState());
                return;
            }
            if (session.isIngestionPaused()) {
                log.debug("platform.auto_heal skip reason=ingestion_paused_by_operator");
                return;
            }

            Map<String, Object> infra = platformMarketFeedService.infrastructureSnapshot();
            @SuppressWarnings("unchecked")
            Map<String, Object> vendors = infra.get("vendors") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : Map.of();
            @SuppressWarnings("unchecked")
            Map<String, Object> zerodha = vendors.get(VENDOR) instanceof Map<?, ?> z
                    ? (Map<String, Object>) z
                    : Map.of();

            boolean operational = Boolean.TRUE.equals(zerodha.get("operationalLivePath"));
            boolean reconnecting = Boolean.TRUE.equals(zerodha.get("reconnecting"));
            boolean platformRefreshed = Boolean.TRUE.equals(tokenSummary.get("platformRefreshed"));

            if (!operational && platformRefreshed && !reconnecting && mayRequestReconnect()) {
                platformMarketFeedService.requestWebsocketReconnect(VENDOR, "platform_auto_heal");
                log.info("platform.auto_heal feed_reconnect_requested detail={}",
                        zerodha.getOrDefault("operationalLivePathDetail", ""));
            }

            if ((operational || feedHealthMonitorService.isHealthyForLiveExecution(Instant.now())) && mayActivatePipeline()) {
                Map<String, Object> activation = signalPipelineActivationService.activate(false, false);
                log.info("platform.auto_heal pipeline_activation {}", activation);
            }
        } catch (Exception ex) {
            log.warn("platform.auto_heal error {}", ex.toString());
        }
    }

    private boolean requiresUserOAuth(PlatformBrokerFeedSession session) {
        String state = session.getConnectionState() != null ? session.getConnectionState().toUpperCase() : "";
        if ("AUTH_EXPIRED".equals(state)) {
            return true;
        }
        boolean hasRefresh = session.getRefreshTokenEnc() != null && !session.getRefreshTokenEnc().isBlank();
        boolean hasAccess = session.getAccessTokenEnc() != null && !session.getAccessTokenEnc().isBlank();
        Instant exp = session.getTokenExpiresAt();
        boolean expired = exp != null && exp.isBefore(Instant.now());
        return !hasRefresh && (!hasAccess || expired);
    }

    private boolean mayRequestReconnect() {
        long now = System.currentTimeMillis();
        long last = lastReconnectRequestMillis.get();
        if (now - last < reconnectCooldownSec * 1000L) {
            return false;
        }
        lastReconnectRequestMillis.set(now);
        return true;
    }

    private boolean mayActivatePipeline() {
        long now = System.currentTimeMillis();
        long last = lastPipelineActivateMillis.get();
        if (now - last < pipelineCooldownSec * 1000L) {
            return false;
        }
        lastPipelineActivateMillis.set(now);
        return true;
    }
}
