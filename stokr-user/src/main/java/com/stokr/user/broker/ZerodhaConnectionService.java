package com.stokr.user.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.common.crypto.FieldCipher;
import com.stokr.common.events.auth.AuthAuditEvents;
import com.stokr.common.exception.BadRequestException;
import com.stokr.user.config.ZerodhaBrokerProperties;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.domain.BrokerOauthState;
import com.stokr.user.repository.BrokerAccountRepository;
import com.stokr.user.repository.BrokerOauthStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.net.URLEncoder;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ZerodhaConnectionService {

    private final ZerodhaBrokerProperties zerodhaBrokerProperties;
    private final BrokerOauthStateRepository oauthStateRepository;
    private final BrokerAccountRepository brokerAccountRepository;
    private final AuthUserRepository authUserRepository;
    private final FieldCipher fieldCipher;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final RestClient http = RestClient.builder().build();

    public ZerodhaAuthorizeDto beginAuthorization(UUID userId) {
        if (!zerodhaBrokerProperties.isConfigured()) {
            throw new BadRequestException("Zerodha API credentials are not configured");
        }
        validateKiteRedirectUrl(zerodhaBrokerProperties.getRedirectUrl());
        AuthUser authUser = authUserRepository.findById(userId).orElseThrow(() -> new BadRequestException("User not found"));
        String state = UUID.randomUUID().toString();
        BrokerOauthState row = new BrokerOauthState();
        row.setUser(authUser);
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
        return new ZerodhaAuthorizeDto(loginUrl, row.getExpiresAt());
    }

    @Transactional
    public UUID completeOAuth(String state, String requestToken) {
        if (state == null || state.isBlank() || requestToken == null || requestToken.isBlank()) {
            throw new BadRequestException("Missing OAuth parameters");
        }
        BrokerOauthState row = oauthStateRepository
                .findByStateTokenAndConsumedFalseAndExpiresAtAfter(state, Instant.now())
                .orElse(null);
        if (row == null) {
            Optional<BrokerOauthState> replay = oauthStateRepository.findByStateToken(state);
            if (replay.isPresent() && replay.get().isConsumed()) {
                UUID uid = replay.get().getUser().getId();
                if (hasActiveZerodhaSession(uid)) {
                    log.info("zerodha.oauth.idempotent_replay userId={}", uid);
                    return uid;
                }
            }
            throw new BadRequestException("Invalid or expired OAuth state");
        }
        UUID userId = row.getUser().getId();

        if (!zerodhaBrokerProperties.isConfigured()) {
            throw new BadRequestException("Zerodha not configured");
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
            String zerodhaMsg = null;
            if (e instanceof org.springframework.web.client.RestClientResponseException rce) {
                try {
                    JsonNode errBody = objectMapper.readTree(rce.getResponseBodyAsString());
                    zerodhaMsg = errBody.path("message").asText(null);
                } catch (Exception ignored) {}
            }
            log.warn("zerodha.session.exchange_failed: {} zerodhaMsg={}", e.getClass().getSimpleName(), zerodhaMsg);
            throw new BadRequestException(
                    zerodhaMsg != null && !zerodhaMsg.isBlank() ? zerodhaMsg : "Could not complete Zerodha login — try again.");
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

            BrokerAccount account = brokerAccountRepository
                    .findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, "ZERODHA")
                    .orElseGet(BrokerAccount::new);
            account.setUserId(userId);
            account.setVendorCode("ZERODHA");
            account.setStatus("CONNECTED");
            account.setHealthStatus("HEALTHY");
            account.setBrokerUserId(kiteUserId);
            account.setAccessTokenEnc(encryptedToken);
            if (refreshToken != null && !refreshToken.isBlank()) {
                account.setRefreshTokenEnc(fieldCipher.encrypt(refreshToken));
            }
            account.setTokenExpiresAt(Instant.now().plus(20, ChronoUnit.HOURS));
            account.setLastSyncAt(Instant.now());
            BrokerAccount saved = brokerAccountRepository.save(account);

            row.setConsumed(true);
            oauthStateRepository.save(row);

            log.info("zerodha.oauth.complete userId={} brokerUserId={} encryptedTokenLength={}",
                    userId, kiteUserId, encryptedToken.length());
            eventPublisher.publishEvent(new AuthAuditEvents.BrokerZerodhaConnected(userId, saved.getId(), Instant.now()));
            return userId;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("zerodha.parse.failed {}", e.getClass().getSimpleName());
            throw new BadRequestException("Invalid Zerodha response");
        }
    }

    private boolean hasActiveZerodhaSession(UUID userId) {
        return brokerAccountRepository
                .findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, "ZERODHA")
                .filter(a -> a.getAccessTokenEnc() != null && !a.getAccessTokenEnc().isBlank())
                .filter(a -> "CONNECTED".equalsIgnoreCase(Optional.ofNullable(a.getStatus()).orElse("")))
                .isPresent();
    }

    /**
     * Kite Connect rejects non-HTTPS redirect URLs; fail fast instead of sending http:// to Zerodha.
     */
    private static void validateKiteRedirectUrl(String redirectUrl) {
        String trimmed = redirectUrl == null ? "" : redirectUrl.strip();
        if (trimmed.isEmpty()) {
            throw new BadRequestException(
                    "STOKR_ZERODHA_REDIRECT_URL is not set. Set it to the full HTTPS OAuth callback URL registered in your Kite app.");
        }
        if (!trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            throw new BadRequestException(
                    "Kite Connect requires STOKR_ZERODHA_REDIRECT_URL to use https:// — Zerodha rejects http:// and other non-HTTPS "
                            + "redirects (error: \"supplied redirect URL is not https\"). Expose your API callback over HTTPS "
                            + "(for example ngrok or Cloudflare Tunnel to this service) or deploy the API behind TLS, and register "
                            + "that exact URL in https://developers.kite.trade/apps .");
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

    public record ZerodhaAuthorizeDto(String authorizeUrl, Instant stateExpiresAt) {
    }
}
