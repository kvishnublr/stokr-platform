package com.stokr.broker;

import com.stokr.external.ZerodhaTokenManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerService {

    private final BrokerAccountRepository repository;
    private final BrokerRegistry registry;
    private final ZerodhaTokenManager zerodhaTokenManager;

    public List<BrokerAccount> getUserBrokers(Long userId) {
        // Only return ACTIVE accounts — disconnected ones shouldn't show
        List<BrokerAccount> active = repository.findByUserIdAndStatus(userId, "ACTIVE");
        // Deduplicate by brokerName: keep the latest (highest id) per broker
        return active.stream()
                .collect(java.util.stream.Collectors.toMap(
                        BrokerAccount::getBrokerName,
                        java.util.function.Function.identity(),
                        (a, b) -> a.getId() > b.getId() ? a : b
                ))
                .values().stream().toList();
    }

    public BrokerAccount getBrokerAccount(Long accountId, Long userId) {
        return repository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Broker account not found"));
    }

    public String getAuthUrl(String brokerName) {
        return registry.getAdapter(brokerName).getAuthUrl();
    }

    @Transactional
    public BrokerAccount completeOAuth(Long userId, String brokerName, String requestToken) {
        BrokerAdapter adapter = registry.getAdapter(brokerName);
        String[] tokens = adapter.exchangeToken(requestToken);

        // Upsert: if user already has an account (any status) for this broker, reactivate it
        BrokerAccount account = repository.findByUserIdAndBrokerNameAndStatus(userId, brokerName.toUpperCase(), "ACTIVE")
                .stream().findFirst().orElse(null);
        if (account == null) {
            // Reactivate a disconnected account instead of creating a new row
            account = repository.findByUserIdAndBrokerName(userId, brokerName.toUpperCase())
                    .stream().findFirst().orElse(null);
            if (account != null) account.setStatus("ACTIVE");
        }

        String accessToken  = tokens[0];
        String refreshToken = tokens.length > 1 && !tokens[1].isBlank() ? tokens[1] : null;
        String clientId     = tokens.length > 2 && !tokens[2].isBlank() ? tokens[2] : null;

        if (account != null) {
            account.setAccessToken(accessToken);
            account.setRefreshToken(refreshToken);
            account.setTokenExpiry(Instant.now().plusSeconds(86400));
            if (clientId != null) account.setClientId(clientId);
        } else {
            account = BrokerAccount.builder()
                    .userId(userId)
                    .brokerName(brokerName.toUpperCase())
                    .accessToken(accessToken)
                    .refreshToken(refreshToken)
                    .clientId(clientId)
                    .tokenExpiry(Instant.now().plusSeconds(86400))
                    .status("ACTIVE")
                    .build();
        }

        BrokerAccount saved = repository.save(account);

        // Immediately push token into ZerodhaTokenManager memory so live engine + backfill work without restart
        if ("ZERODHA".equalsIgnoreCase(brokerName)) {
            zerodhaTokenManager.setAuth(accessToken, refreshToken, 86400);
            log.info("ZerodhaTokenManager in-memory token updated for user {}", userId);
        }

        return saved;
    }

    @Transactional
    public void disconnectBroker(Long accountId, Long userId) {
        BrokerAccount account = getBrokerAccount(accountId, userId);
        account.setStatus("DISCONNECTED");
        account.setAccessToken(null);
        account.setRefreshToken(null);
        repository.save(account);
        log.info("Disconnected broker account {} for user {}", accountId, userId);
    }

    public BrokerAdapter getAdapter(String brokerName) {
        return registry.getAdapter(brokerName);
    }

    public List<String> getSupportedBrokers() {
        return registry.getSupportedBrokers();
    }

    public List<BrokerAccount> getActiveAccountsByBroker(String brokerName) {
        return repository.findByBrokerNameAndStatus(brokerName.toUpperCase(), "ACTIVE");
    }

    public List<BrokerAccount> getAllActiveAccounts() {
        return repository.findByStatus("ACTIVE");
    }
}
