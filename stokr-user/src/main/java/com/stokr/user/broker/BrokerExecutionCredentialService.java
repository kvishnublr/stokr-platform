package com.stokr.user.broker;

import com.stokr.common.crypto.FieldCipher;
import com.stokr.user.config.ZerodhaBrokerProperties;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.repository.BrokerAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves decrypted broker credentials for LIVE order submission.
 * Fallback order: order user → configured primary trader.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrokerExecutionCredentialService {

    private final BrokerAccountRepository brokerAccountRepository;
    private final FieldCipher fieldCipher;
    private final ZerodhaBrokerProperties zerodhaBrokerProperties;

    @Value("${stokr.strategy.primary-trader-user-id:}")
    private String primaryTraderUserIdRaw;

    public record ResolvedCredentials(UUID credentialUserId, String apiKey, String accessToken) {}

    public Optional<ResolvedCredentials> resolve(UUID orderUserId, String vendor) {
        String normalizedVendor = vendor != null && !vendor.isBlank() ? vendor.trim() : "ZERODHA";
        if (orderUserId != null) {
            Optional<ResolvedCredentials> direct = resolveForUser(orderUserId, normalizedVendor, "order_user");
            if (direct.isPresent()) {
                return direct;
            }
        }
        UUID primaryTrader = parsePrimaryTraderUserId();
        if (primaryTrader != null && (orderUserId == null || !primaryTrader.equals(orderUserId))) {
            Optional<ResolvedCredentials> fallback = resolveForUser(primaryTrader, normalizedVendor, "primary_trader");
            if (fallback.isPresent()) {
                log.info("broker.creds.fallback orderUserId={} credentialUserId={} vendor={}",
                        orderUserId, primaryTrader, normalizedVendor);
                return fallback;
            }
        }
        log.warn("broker.creds.unresolved orderUserId={} vendor={}", orderUserId, normalizedVendor);
        return Optional.empty();
    }

    public Optional<UUID> primaryTraderUserId() {
        return Optional.ofNullable(parsePrimaryTraderUserId());
    }

    private Optional<ResolvedCredentials> resolveForUser(UUID userId, String vendor, String source) {
        Optional<BrokerAccount> accountOpt = brokerAccountRepository
                .findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, vendor);
        if (accountOpt.isEmpty()) {
            return Optional.empty();
        }
        BrokerAccount account = accountOpt.get();
        if (!brokerReady(account)) {
            log.warn("broker.creds.not_ready userId={} vendor={} status={} source={}",
                    userId, vendor, account.getStatus(), source);
            return Optional.empty();
        }
        String accessToken = decryptAccessToken(account);
        if (accessToken == null || accessToken.isBlank()) {
            log.warn("broker.creds.no_token userId={} vendor={} source={}", userId, vendor, source);
            return Optional.empty();
        }
        String apiKey = zerodhaBrokerProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("broker.creds.no_api_key userId={} vendor={} source={}", userId, vendor, source);
            return Optional.empty();
        }
        return Optional.of(new ResolvedCredentials(userId, apiKey, accessToken));
    }

    private String decryptAccessToken(BrokerAccount account) {
        try {
            if (account.getAccessTokenEnc() != null && !account.getAccessTokenEnc().isBlank()) {
                if (account.getTokenExpiresAt() != null && account.getTokenExpiresAt().isBefore(Instant.now())) {
                    return null;
                }
                return decodeStoredBrokerToken(account.getAccessTokenEnc());
            }
        } catch (Exception ex) {
            log.error("broker.creds.decrypt_failed userId={} {}", account.getUserId(), ex.getMessage());
        }
        return null;
    }

    private String decodeStoredBrokerToken(String stored) {
        try {
            return fieldCipher.decrypt(stored);
        } catch (RuntimeException ex) {
            String trimmed = stored == null ? "" : stored.trim();
            int colon = trimmed.indexOf(':');
            return colon >= 0 && colon + 1 < trimmed.length()
                    ? trimmed.substring(colon + 1).trim()
                    : trimmed;
        }
    }

    private static boolean brokerReady(BrokerAccount account) {
        String status = account.getStatus();
        if (status == null || status.isBlank()) {
            return false;
        }
        return switch (status.trim().toUpperCase()) {
            case "CONNECTED", "ACTIVE", "LINKED", "OK" -> true;
            default -> false;
        };
    }

    private UUID parsePrimaryTraderUserId() {
        if (primaryTraderUserIdRaw == null || primaryTraderUserIdRaw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(primaryTraderUserIdRaw.trim());
        } catch (IllegalArgumentException ex) {
            log.warn("broker.creds.invalid_primary_trader_user_id value={}", primaryTraderUserIdRaw);
            return null;
        }
    }
}
