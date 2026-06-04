package com.stokr.user.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.common.crypto.FieldCipher;
import com.stokr.common.events.PlatformFeedReconnectRequestedEvent;
import com.stokr.common.exception.BadRequestException;
import com.stokr.user.config.ZerodhaBrokerProperties;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.domain.PlatformBrokerFeedSession;
import com.stokr.user.domain.PlatformBrokerOauthState;
import com.stokr.user.repository.BrokerAccountRepository;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import com.stokr.user.repository.PlatformBrokerOauthStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin-owned platform market feed (OAuth + session row). Separate from trader {@link com.stokr.user.domain.BrokerAccount}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformMarketFeedService {

    private static final List<String> VENDORS = List.of("ZERODHA", "UPSTOX", "ANGEL", "DHAN");

    private final ZerodhaBrokerProperties zerodhaBrokerProperties;
    private final FieldCipher fieldCipher;
    private final ObjectMapper objectMapper;
    private final PlatformBrokerFeedSessionRepository sessionRepository;
    private final PlatformBrokerOauthStateRepository oauthStateRepository;
    private final BrokerAccountRepository brokerAccountRepository;
    private final ZerodhaKiteApiClient kiteApiClient;
    private final ApplicationEventPublisher eventPublisher;
    private final RestClient http = RestClient.builder().build();

    public boolean isPlatformOauthState(String state) {
        if (state == null || state.isBlank()) {
            return false;
        }
        return oauthStateRepository.findByStateToken(state)
                .filter(r -> "ZERODHA".equalsIgnoreCase(r.getVendorCode()))
                .isPresent();
    }

    /**
     * @return true if this callback was consumed as a platform-feed OAuth completion (trader flow should not run).
     */
    @Transactional
    public boolean tryCompleteZerodhaFromOAuthCallback(String state, String requestToken) {
        Optional<PlatformBrokerOauthState> opt =
                oauthStateRepository.findByStateTokenAndConsumedFalseAndExpiresAtAfter(state, Instant.now());
        if (opt.isEmpty()) {
            return false;
        }
        PlatformBrokerOauthState row = opt.get();
        if (!"ZERODHA".equalsIgnoreCase(row.getVendorCode())) {
            return false;
        }
        if (!zerodhaBrokerProperties.isConfigured()) {
            throw new BadRequestException("Zerodha API credentials are not configured");
        }
        String checksum = sha256Hex(
                zerodhaBrokerProperties.getApiKey() + requestToken + zerodhaBrokerProperties.getApiSecret()
        );
        String sessionBody;
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("api_key", zerodhaBrokerProperties.getApiKey());
            form.add("request_token", requestToken);
            form.add("checksum", checksum);
            sessionBody = http.post()
                    .uri("https://api.kite.trade/session/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            String zerodhaMsg = extractZerodhaMessage(e);
            log.warn("platform.zerodha.session.exchange_failed: {} zerodhaMsg={}",
                    e.getClass().getSimpleName(), zerodhaMsg);
            throw new BadRequestException(
                    zerodhaMsg != null && !zerodhaMsg.isBlank()
                            ? zerodhaMsg
                            : "Could not complete Zerodha platform login — try again.");
        }
        try {
            JsonNode root = objectMapper.readTree(sessionBody != null ? sessionBody : "{}");
            if (!"success".equalsIgnoreCase(root.path("status").asText())) {
                throw new BadRequestException("Zerodha rejected token exchange");
            }
            JsonNode data = root.path("data");
            String accessToken = data.path("access_token").asText(null);
            String refreshToken = data.path("refresh_token").asText(null);
            String kiteUserId = data.path("user_id").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new BadRequestException("Missing access_token from Zerodha");
            }
            String encryptedToken = fieldCipher.encrypt(accessToken);
            if (encryptedToken == null || encryptedToken.isBlank()) {
                throw new BadRequestException("Token encryption failed");
            }
            PlatformBrokerFeedSession s = sessionRepository
                    .findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc("ZERODHA")
                    .orElseGet(PlatformBrokerFeedSession::new);
            s.setVendorCode("ZERODHA");
            s.setConnectionState("CONNECTED");
            s.setWebsocketState("CLOSED");
            s.setAccessTokenEnc(encryptedToken);
            if (refreshToken != null && !refreshToken.isBlank()) {
                s.setRefreshTokenEnc(fieldCipher.encrypt(refreshToken));
            }
            s.setTokenExpiresAt(Instant.now().plus(20, ChronoUnit.HOURS));
            s.setLastSyncAt(Instant.now());
            s.setInstrumentSyncState("UNKNOWN");
            sessionRepository.save(s);

            row.setConsumed(true);
            oauthStateRepository.save(row);
            log.info("platform.zerodha.oauth.complete kiteUserId={}", kiteUserId);
            return true;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("platform.zerodha.parse.failed {}", e.getClass().getSimpleName());
            throw new BadRequestException("Invalid Zerodha response");
        }
    }

    private String extractZerodhaMessage(Exception e) {
        if (!(e instanceof org.springframework.web.client.RestClientResponseException rce)) {
            return null;
        }
        try {
            JsonNode errBody = objectMapper.readTree(rce.getResponseBodyAsString());
            String msg = errBody.path("message").asText(null);
            if (msg != null && !msg.isBlank()) {
                return msg;
            }
            String errorType = errBody.path("error_type").asText(null);
            if (errorType != null && !errorType.isBlank()) {
                return errorType;
            }
        } catch (Exception ignored) {
            // fall through to generic message
        }
        return null;
    }

    @Transactional
    public Map<String, Object> beginZerodhaPlatformConnect() {
        if (!zerodhaBrokerProperties.isConfigured()) {
            throw new BadRequestException("Zerodha API credentials are not configured");
        }
        validateKiteRedirectUrl(zerodhaBrokerProperties.getRedirectUrl());
        String state = UUID.randomUUID().toString();
        PlatformBrokerOauthState row = new PlatformBrokerOauthState();
        row.setVendorCode("ZERODHA");
        row.setStateToken(state);
        row.setExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES));
        row.setConsumed(false);
        oauthStateRepository.save(row);
        String callbackUrlWithState = UriComponentsBuilder
                .fromUriString(zerodhaBrokerProperties.getRedirectUrl())
                .replaceQueryParam("state", state)
                .build()
                .toUriString();
        String loginUrl = "https://kite.zerodha.com/connect/login?v=3&api_key="
                + URLEncoder.encode(zerodhaBrokerProperties.getApiKey(), StandardCharsets.UTF_8)
                + "&redirect_url="
                + URLEncoder.encode(callbackUrlWithState, StandardCharsets.UTF_8)
                + "&state="
                + URLEncoder.encode(state, StandardCharsets.UTF_8);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vendor", "ZERODHA");
        out.put("authorizeUrl", loginUrl);
        out.put("stateExpiresAt", row.getExpiresAt().toString());
        out.put("oauthCallback", "Uses the same registered Kite redirect URL as traders; completion is routed by OAuth state (platform vs trader).");
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> infrastructureSnapshot() {
        Map<String, Object> vendors = new LinkedHashMap<>();
        for (String v : VENDORS) {
            vendors.put(v, vendorPayload(v));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("collectedAt", Instant.now().toString());
        root.put("plane", "PLATFORM_MARKET_FEED");
        root.put("note", "This payload reflects platform_broker_feed_sessions only — not trader BrokerAccount execution rows.");
        root.put("vendors", vendors);
        return root;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> vendorSnapshot(String vendor) {
        String v = normalizeVendor(vendor);
        return vendorPayload(v);
    }

    @Transactional
    public Map<String, Object> disconnect(String vendor) {
        String v = normalizeVendor(vendor);
        PlatformBrokerFeedSession s = sessionRepository.findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(v).orElse(null);
        if (s == null) {
            return Map.of("vendor", v, "disconnected", false, "detail", "no platform session row");
        }
        s.setAccessTokenEnc(null);
        s.setRefreshTokenEnc(null);
        s.setConnectionState("DISCONNECTED");
        s.setWebsocketState("CLOSED");
        s.setTokenExpiresAt(null);
        s.setLastTickAt(null);
        sessionRepository.save(s);
        return Map.of("vendor", v, "disconnected", true);
    }

    @Transactional
    public Map<String, Object> setIngestionPaused(String vendor, boolean paused) {
        String v = normalizeVendor(vendor);
        PlatformBrokerFeedSession s = sessionRepository
                .findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(v)
                .orElseThrow(() -> new BadRequestException("No platform session for vendor"));
        s.setIngestionPaused(paused);
        sessionRepository.save(s);
        return Map.of(
                "vendor", v,
                "ingestionPaused", paused,
                "ingestionPauseEnforcedByWorkers", false,
                "detail", "Flag persisted — ingestion workers must read this row in a future release to enforce pause."
        );
    }

    @Transactional
    public Map<String, Object> refreshFromKite(String vendor) {
        String v = normalizeVendor(vendor);
        if (!"ZERODHA".equals(v)) {
            throw new BadRequestException("Live refresh implemented for ZERODHA only in this build");
        }
        Map<String, Object> refreshSummary = refreshAllZerodhaTokens(Duration.ofHours(2));
        PlatformBrokerFeedSession s = sessionRepository
                .findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(v)
                .orElseThrow(() -> new BadRequestException("No platform session for vendor"));
        if (s.getAccessTokenEnc() == null || s.getAccessTokenEnc().isBlank()) {
            throw new BadRequestException("No access token on platform session after refresh attempt");
        }
        String accessToken = fieldCipher.decrypt(s.getAccessTokenEnc());
        JsonNode profile = kiteApiClient.getProfile(zerodhaBrokerProperties.getApiKey(), accessToken);
        if (!"success".equalsIgnoreCase(profile.path("status").asText())) {
            s.setConnectionState("DEGRADED");
            sessionRepository.save(s);
            throw new BadRequestException("Kite profile rejected: " + profile.path("message").asText("unknown"));
        }
        s.setLastSyncAt(Instant.now());
        if (s.getTokenExpiresAt() != null && s.getTokenExpiresAt().isBefore(Instant.now())) {
            s.setConnectionState("AUTH_EXPIRED");
        } else {
            s.setConnectionState("CONNECTED");
        }
        sessionRepository.save(s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vendor", v);
        out.put("ok", true);
        out.put("lastSyncAt", s.getLastSyncAt().toString());
        out.put("tokenRefresh", refreshSummary);
        return out;
    }

    public Map<String, Object> notImplemented(String action) {
        return Map.of(
                "ok", false,
                "action", action,
                "detail", "Not implemented in this build — no in-process websocket / instrument sync worker wired to platform session yet."
        );
    }

    public Map<String, Object> requestWebsocketReconnect(String vendor, String reason) {
        String v = normalizeVendor(vendor);
        if (!"ZERODHA".equals(v)) {
            throw new BadRequestException("WebSocket reconnect implemented for ZERODHA only in this build");
        }
        sessionRepository.findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(v)
                .orElseThrow(() -> new BadRequestException("No platform session for vendor"));
        eventPublisher.publishEvent(new PlatformFeedReconnectRequestedEvent(v, reason));
        return Map.of("vendor", v, "ok", true, "detail", "WebSocket reconnect requested");
    }

    /**
     * Refreshes platform Zerodha access token when missing/near expiry using stored refresh token.
     * Returns true when token is valid after this call.
     */
    @Transactional
    public boolean ensureValidPlatformZerodhaToken(Duration refreshBefore) {
        PlatformBrokerFeedSession s = sessionRepository
                .findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc("ZERODHA")
                .orElse(null);
        if (s == null) {
            return false;
        }
        Instant now = Instant.now();
        Instant expiresAt = s.getTokenExpiresAt();
        boolean hasToken = s.getAccessTokenEnc() != null && !s.getAccessTokenEnc().isBlank();
        boolean tokenStillValid = hasToken && expiresAt != null && expiresAt.isAfter(now.plus(refreshBefore));
        if (tokenStillValid) {
            return true;
        }
        if (s.getRefreshTokenEnc() == null || s.getRefreshTokenEnc().isBlank()) {
            log.warn("platform.zerodha.token_refresh_skipped reason=no_refresh_token");
            if (!hasToken || (expiresAt != null && !expiresAt.isAfter(now))) {
                s.setConnectionState("AUTH_EXPIRED");
                sessionRepository.save(s);
            }
            return false;
        }
        try {
            String refreshToken = fieldCipher.decrypt(s.getRefreshTokenEnc());
            if (refreshToken == null || refreshToken.isBlank()) {
                s.setConnectionState("AUTH_EXPIRED");
                sessionRepository.save(s);
                return false;
            }
            JsonNode renewed = kiteApiClient.renewAccessToken(
                    zerodhaBrokerProperties.getApiKey(),
                    zerodhaBrokerProperties.getApiSecret(),
                    refreshToken
            );
            if (!"success".equalsIgnoreCase(renewed.path("status").asText())) {
                log.warn("platform.zerodha.token_refresh_failed msg={}", renewed.path("message").asText("unknown"));
                s.setConnectionState("AUTH_EXPIRED");
                sessionRepository.save(s);
                return false;
            }
            JsonNode data = renewed.path("data");
            String newAccessToken = data.path("access_token").asText(null);
            String newRefreshToken = data.path("refresh_token").asText(null);
            if (newAccessToken == null || newAccessToken.isBlank()) {
                log.warn("platform.zerodha.token_refresh_failed msg=missing_access_token");
                s.setConnectionState("AUTH_EXPIRED");
                sessionRepository.save(s);
                return false;
            }
            s.setAccessTokenEnc(fieldCipher.encrypt(newAccessToken));
            if (newRefreshToken != null && !newRefreshToken.isBlank()) {
                s.setRefreshTokenEnc(fieldCipher.encrypt(newRefreshToken));
            }
            // Zerodha access tokens are day-scoped; keep a safe rolling expiry window.
            s.setTokenExpiresAt(now.plus(20, ChronoUnit.HOURS));
            s.setConnectionState("CONNECTED");
            s.setLastSyncAt(now);
            sessionRepository.save(s);
            log.info("platform.zerodha.token_refreshed expiresAt={}", s.getTokenExpiresAt());
            return true;
        } catch (Exception ex) {
            log.warn("platform.zerodha.token_refresh_error {}", ex.toString());
            s.setConnectionState("AUTH_EXPIRED");
            sessionRepository.save(s);
            return false;
        }
    }

    /**
     * Renews platform and all connected trader Zerodha sessions when tokens are missing or near expiry.
     * Works without the live WebSocket runtime — intended for schedulers and admin refresh actions.
     */
    @Transactional
    public Map<String, Object> refreshAllZerodhaTokens(Duration refreshBefore) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("at", Instant.now().toString());

        ensureSessionFromTraderFallback("ZERODHA");
        boolean platformOk = ensureValidPlatformZerodhaToken(refreshBefore);
        summary.put("platformRefreshed", platformOk);

        int traderAttempted = 0;
        int traderRefreshed = 0;
        int traderFailed = 0;
        for (BrokerAccount account : brokerAccountRepository.findAllByVendorCodeIgnoreCaseAndDeletedFalse("ZERODHA")) {
            if (account.getAccessTokenEnc() == null || account.getAccessTokenEnc().isBlank()) {
                continue;
            }
            if (!"CONNECTED".equalsIgnoreCase(Optional.ofNullable(account.getStatus()).orElse(""))) {
                continue;
            }
            traderAttempted++;
            if (ensureValidTraderZerodhaToken(account, refreshBefore)) {
                traderRefreshed++;
            } else {
                traderFailed++;
            }
        }
        summary.put("traderAttempted", traderAttempted);
        summary.put("traderRefreshed", traderRefreshed);
        summary.put("traderFailed", traderFailed);

        syncPlatformTokensToTraders();
        summary.put("platformSyncedToTraders", true);
        return summary;
    }

    /**
     * Refreshes a single trader Zerodha session using stored refresh token (bootstrapped from platform when missing).
     */
    @Transactional
    public boolean ensureValidTraderZerodhaToken(BrokerAccount account, Duration refreshBefore) {
        if (account == null) {
            return false;
        }
        Instant now = Instant.now();
        Instant expiresAt = account.getTokenExpiresAt();
        boolean hasToken = account.getAccessTokenEnc() != null && !account.getAccessTokenEnc().isBlank();
        boolean tokenStillValid = hasToken && expiresAt != null && expiresAt.isAfter(now.plus(refreshBefore));
        if (tokenStillValid) {
            return true;
        }
        bootstrapTraderRefreshFromPlatform(account);
        if (account.getRefreshTokenEnc() == null || account.getRefreshTokenEnc().isBlank()) {
            log.warn("trader.zerodha.token_refresh_skipped userId={} reason=no_refresh_token", account.getUserId());
            if (!hasToken || (expiresAt != null && !expiresAt.isAfter(now))) {
                account.setHealthStatus("DEGRADED");
                brokerAccountRepository.save(account);
            }
            return false;
        }
        try {
            String refreshToken = fieldCipher.decrypt(account.getRefreshTokenEnc());
            if (refreshToken == null || refreshToken.isBlank()) {
                account.setHealthStatus("DEGRADED");
                brokerAccountRepository.save(account);
                return false;
            }
            JsonNode renewed = kiteApiClient.renewAccessToken(
                    zerodhaBrokerProperties.getApiKey(),
                    zerodhaBrokerProperties.getApiSecret(),
                    refreshToken
            );
            if (!"success".equalsIgnoreCase(renewed.path("status").asText())) {
                log.warn("trader.zerodha.token_refresh_failed userId={} msg={}",
                        account.getUserId(), renewed.path("message").asText("unknown"));
                account.setHealthStatus("DEGRADED");
                brokerAccountRepository.save(account);
                return false;
            }
            JsonNode data = renewed.path("data");
            String newAccessToken = data.path("access_token").asText(null);
            String newRefreshToken = data.path("refresh_token").asText(null);
            if (newAccessToken == null || newAccessToken.isBlank()) {
                account.setHealthStatus("DEGRADED");
                brokerAccountRepository.save(account);
                return false;
            }
            account.setAccessTokenEnc(fieldCipher.encrypt(newAccessToken));
            if (newRefreshToken != null && !newRefreshToken.isBlank()) {
                account.setRefreshTokenEnc(fieldCipher.encrypt(newRefreshToken));
            }
            account.setTokenExpiresAt(now.plus(20, ChronoUnit.HOURS));
            account.setStatus("CONNECTED");
            account.setHealthStatus("HEALTHY");
            account.setLastSyncAt(now);
            brokerAccountRepository.save(account);
            log.info("trader.zerodha.token_refreshed userId={} expiresAt={}", account.getUserId(), account.getTokenExpiresAt());
            return true;
        } catch (Exception ex) {
            log.warn("trader.zerodha.token_refresh_error userId={} {}", account.getUserId(), ex.toString());
            account.setHealthStatus("DEGRADED");
            brokerAccountRepository.save(account);
            return false;
        }
    }

    /**
     * After platform renewal, keep trader execution rows aligned so broker rails stay authenticated.
     */
    @Transactional
    public void syncPlatformTokensToTraders() {
        PlatformBrokerFeedSession platform = sessionRepository
                .findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc("ZERODHA")
                .orElse(null);
        if (platform == null || platform.getAccessTokenEnc() == null || platform.getAccessTokenEnc().isBlank()) {
            return;
        }
        Instant exp = platform.getTokenExpiresAt();
        if (exp != null && exp.isBefore(Instant.now())) {
            return;
        }
        for (BrokerAccount account : brokerAccountRepository.findAllByVendorCodeIgnoreCaseAndDeletedFalse("ZERODHA")) {
            if (!"CONNECTED".equalsIgnoreCase(Optional.ofNullable(account.getStatus()).orElse(""))) {
                continue;
            }
            boolean needsSync = account.getAccessTokenEnc() == null
                    || account.getAccessTokenEnc().isBlank()
                    || account.getTokenExpiresAt() == null
                    || account.getTokenExpiresAt().isBefore(Instant.now().plus(Duration.ofHours(1)));
            if (!needsSync) {
                continue;
            }
            account.setAccessTokenEnc(platform.getAccessTokenEnc());
            if (platform.getRefreshTokenEnc() != null && !platform.getRefreshTokenEnc().isBlank()) {
                account.setRefreshTokenEnc(platform.getRefreshTokenEnc());
            }
            account.setTokenExpiresAt(platform.getTokenExpiresAt());
            account.setLastSyncAt(Instant.now());
            account.setHealthStatus("HEALTHY");
            brokerAccountRepository.save(account);
            log.info("trader.zerodha.token_synced_from_platform userId={}", account.getUserId());
        }
    }

    private void bootstrapTraderRefreshFromPlatform(BrokerAccount account) {
        if (account.getRefreshTokenEnc() != null && !account.getRefreshTokenEnc().isBlank()) {
            return;
        }
        PlatformBrokerFeedSession platform = sessionRepository
                .findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc("ZERODHA")
                .orElse(null);
        if (platform == null || platform.getRefreshTokenEnc() == null || platform.getRefreshTokenEnc().isBlank()) {
            return;
        }
        account.setRefreshTokenEnc(platform.getRefreshTokenEnc());
        if (account.getAccessTokenEnc() == null || account.getAccessTokenEnc().isBlank()) {
            account.setAccessTokenEnc(platform.getAccessTokenEnc());
            account.setTokenExpiresAt(platform.getTokenExpiresAt());
        }
        brokerAccountRepository.save(account);
        log.info("trader.zerodha.refresh_bootstrapped_from_platform userId={}", account.getUserId());
    }

    private Map<String, Object> vendorPayload(String vendor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vendorCode", vendor);
        m.put("role", "PLATFORM_MARKET_FEED");
        ensureSessionFromTraderFallback(vendor);
        Optional<PlatformBrokerFeedSession> opt = sessionRepository.findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(vendor);
        if (opt.isEmpty()) {
            m.put("connectionState", "DISCONNECTED");
            m.put("websocketState", "CLOSED");
            m.put("configured", false);
            m.put("hasRefreshToken", false);
            m.put("operationalLivePath", false);
            m.put("operationalLivePathDetail", "No platform session row — connect OAuth from admin broker infrastructure.");
            m.put("detail", "No platform session row — use Connect (Zerodha) from admin broker infrastructure.");
            return m;
        }
        PlatformBrokerFeedSession s = opt.get();
        m.put("configured", s.getAccessTokenEnc() != null && !s.getAccessTokenEnc().isBlank());
        m.put("hasRefreshToken", s.getRefreshTokenEnc() != null && !s.getRefreshTokenEnc().isBlank());
        m.put("connectionState", s.getConnectionState());
        m.put("websocketState", s.getWebsocketState());
        m.put("tokenExpiresAt", s.getTokenExpiresAt() != null ? s.getTokenExpiresAt().toString() : null);
        m.put("lastTickAt", s.getLastTickAt() != null ? s.getLastTickAt().toString() : null);
        m.put("lastPacketAt", s.getLastPacketAt() != null ? s.getLastPacketAt().toString() : null);
        m.put("lastHeartbeatAt", s.getLastHeartbeatAt() != null ? s.getLastHeartbeatAt().toString() : null);
        m.put("lastSyncAt", s.getLastSyncAt() != null ? s.getLastSyncAt().toString() : null);
        m.put("reconnectCount", s.getReconnectCount());
        m.put("subscriptionCount", s.getSubscriptionCount());
        m.put("packetsPerSec", s.getPacketsPerSec());
        m.put("ticksPerSec", s.getTicksPerSec());
        m.put("feedLagMs", s.getFeedLagMs());
        m.put("disconnectReason", s.getDisconnectReason());
        m.put("reconnecting", s.isReconnecting());
        m.put("tickProcessingLatencyMs", s.getTickProcessingLatencyMs());
        m.put("ingestionPaused", s.isIngestionPaused());
        m.put("ingestionPauseEnforcedByWorkers", false);
        m.put("instrumentSyncState", s.getInstrumentSyncState());
        m.put("telemetryJson", s.getTelemetryJson());
        overlayDecodedTelemetry(m, s.getTelemetryJson());
        Instant hbAt = s.getLastHeartbeatAt();
        m.put("heartbeatAgeSeconds", hbAt == null ? null : Duration.between(hbAt, Instant.now()).getSeconds());
        Instant tickAt = s.getLastTickAt();
        m.put("latestTickAgeSeconds", tickAt == null ? null : Duration.between(tickAt, Instant.now()).getSeconds());
        String ws = s.getWebsocketState() != null ? s.getWebsocketState() : "";
        m.put("websocketConnected", ws.equalsIgnoreCase("OPEN") || ws.equalsIgnoreCase("CONNECTED"));

        boolean configured = Boolean.TRUE.equals(m.get("configured"));
        Instant exp = s.getTokenExpiresAt();
        boolean tokenValid = configured && exp != null && exp.isAfter(Instant.now());
        boolean wsLive = ws.equalsIgnoreCase("OPEN") || ws.equalsIgnoreCase("CONNECTED");
        Instant lastTick = s.getLastTickAt();
        long tickAgeSec = lastTick == null ? Long.MAX_VALUE : Duration.between(lastTick, Instant.now()).getSeconds();
        boolean tickFresh = lastTick != null && tickAgeSec <= 120;
        double pps = toDouble(s.getPacketsPerSec());
        boolean packetsFlowing = pps > 0.05;
        int subs = s.getSubscriptionCount();
        boolean subsOk = subs > 0;
        boolean notPaused = !s.isIngestionPaused();
        boolean operationalLivePath = notPaused && tokenValid && wsLive && tickFresh && packetsFlowing && subsOk;
        m.put("operationalLivePath", operationalLivePath);
        if (!notPaused) {
            m.put("operationalLivePathDetail", "Operator paused platform ingestion (ingestionPaused=true)");
        } else if (!tokenValid) {
            m.put("operationalLivePathDetail", !configured ? "No OAuth token on platform session" : "Access token expired or missing expiry");
        } else if (!wsLive) {
            m.put("operationalLivePathDetail", "Vendor websocket not open (state=" + ws + ")");
        } else if (!tickFresh) {
            m.put("operationalLivePathDetail", "No tick within freshness window (~120s since lastTickAt)");
        } else if (!packetsFlowing) {
            m.put("operationalLivePathDetail", "No measurable packet rate (packetsPerSec)");
        } else if (!subsOk) {
            m.put("operationalLivePathDetail", "No active instrument subscriptions");
        } else {
            m.put("operationalLivePathDetail", "Token valid, WS open, ticks fresh, packets and subscriptions present");
        }
        return m;
    }

    @Transactional
    public boolean ensureSessionFromTraderFallback(String vendor) {
        String normalized = normalizeVendor(vendor);
        if (!"ZERODHA".equals(normalized)) {
            return false;
        }
        PlatformBrokerFeedSession existing = sessionRepository
                .findFirstByVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(normalized)
                .orElse(null);
        if (existing != null && existing.getAccessTokenEnc() != null && !existing.getAccessTokenEnc().isBlank()) {
            return false;
        }
        BrokerAccount trader = brokerAccountRepository
                .findFirstByVendorCodeIgnoreCaseAndDeletedFalseAndStatusIgnoreCaseAndAccessTokenEncIsNotNullOrderByUpdatedAtDesc(
                        normalized,
                        "CONNECTED"
                )
                .orElse(null);
        if (trader == null || trader.getAccessTokenEnc() == null || trader.getAccessTokenEnc().isBlank()) {
            return false;
        }
        PlatformBrokerFeedSession target = existing != null ? existing : new PlatformBrokerFeedSession();
        target.setVendorCode(normalized);
        target.setAccessTokenEnc(trader.getAccessTokenEnc());
        target.setRefreshTokenEnc(trader.getRefreshTokenEnc());
        target.setTokenExpiresAt(trader.getTokenExpiresAt());
        target.setConnectionState("CONNECTED");
        target.setWebsocketState("CLOSED");
        target.setInstrumentSyncState("UNKNOWN");
        target.setLastSyncAt(Instant.now());
        sessionRepository.save(target);
        log.info("platform.feed.session_bootstrap_from_trader vendor={} brokerUserId={}", normalized, trader.getBrokerUserId());
        return true;
    }

    private void overlayDecodedTelemetry(Map<String, Object> m, String telemetryJson) {
        if (telemetryJson == null || telemetryJson.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(telemetryJson);
            String sym = root.path("streamingSymbols").asText(null);
            if (sym != null && !sym.isBlank()) {
                m.put("streamingSymbols", sym);
            }
        } catch (Exception e) {
            log.debug("platform.feed.telemetry_parse {}", e.toString());
        }
    }

    private static double toDouble(Number n) {
        return n == null ? 0.0 : n.doubleValue();
    }

    private static String normalizeVendor(String vendor) {
        if (vendor == null || vendor.isBlank()) {
            throw new BadRequestException("vendor is required");
        }
        return vendor.trim().toUpperCase();
    }

    private static void validateKiteRedirectUrl(String redirectUrl) {
        String trimmed = redirectUrl == null ? "" : redirectUrl.strip();
        if (trimmed.isEmpty()) {
            throw new BadRequestException("Zerodha redirect URL is not set");
        }
        if (!trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            throw new BadRequestException("Kite requires https redirect URLs for OAuth");
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
