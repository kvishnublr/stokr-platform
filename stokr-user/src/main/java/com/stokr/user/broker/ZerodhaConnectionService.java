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
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.net.URLEncoder;
import java.util.HexFormat;
import java.util.Map;
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
        AuthUser authUser = authUserRepository.findById(userId).orElseThrow(() -> new BadRequestException("User not found"));
        String state = UUID.randomUUID().toString();
        BrokerOauthState row = new BrokerOauthState();
        row.setUser(authUser);
        row.setStateToken(state);
        row.setExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES));
        row.setConsumed(false);
        oauthStateRepository.save(row);
        String loginUrl = "https://kite.zerodha.com/connect/login?v=3&api_key="
                + URLEncoder.encode(zerodhaBrokerProperties.getApiKey(), StandardCharsets.UTF_8)
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
                .orElseThrow(() -> new BadRequestException("Invalid or expired OAuth state"));
        row.setConsumed(true);
        oauthStateRepository.save(row);
        UUID userId = row.getUser().getId();

        if (!zerodhaBrokerProperties.isConfigured()) {
            throw new BadRequestException("Zerodha not configured");
        }

        String checksum = sha256Hex(
                zerodhaBrokerProperties.getApiKey() + requestToken + zerodhaBrokerProperties.getApiSecret()
        );

        String sessionBody;
        try {
            sessionBody = http.post()
                    .uri("https://api.kite.trade/session/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(Map.of(
                            "api_key", zerodhaBrokerProperties.getApiKey(),
                            "request_token", requestToken,
                            "checksum", checksum
                    )))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("zerodha.session.exchange_failed {}", e.toString());
            throw new BadRequestException("Could not complete Zerodha login — try again.");
        }

        try {
            JsonNode root = objectMapper.readTree(sessionBody != null ? sessionBody : "{}");
            if (!"success".equalsIgnoreCase(root.path("status").asText())) {
                throw new BadRequestException("Zerodha rejected token exchange");
            }
            JsonNode data = root.path("data");
            String accessToken = data.path("access_token").asText(null);
            String kiteUserId = data.path("user_id").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new BadRequestException("Missing access_token from Zerodha");
            }

            BrokerAccount account = brokerAccountRepository
                    .findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, "ZERODHA")
                    .orElseGet(BrokerAccount::new);
            account.setUserId(userId);
            account.setVendorCode("ZERODHA");
            account.setStatus("CONNECTED");
            account.setHealthStatus("HEALTHY");
            account.setBrokerUserId(kiteUserId);
            account.setAccessTokenEnc(fieldCipher.encrypt(accessToken));
            account.setTokenExpiresAt(Instant.now().plus(12, ChronoUnit.HOURS));
            account.setLastSyncAt(Instant.now());
            brokerAccountRepository.save(account);

            eventPublisher.publishEvent(new AuthAuditEvents.BrokerZerodhaConnected(userId, account.getId(), Instant.now()));
            return userId;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("zerodha.parse.failed {}", e.toString());
            throw new BadRequestException("Invalid Zerodha response");
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
