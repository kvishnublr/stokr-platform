package com.stokr.broker;

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

    public List<BrokerAccount> getUserBrokers(Long userId) {
        return repository.findByUserId(userId);
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

        BrokerAccount account = BrokerAccount.builder()
                .userId(userId)
                .brokerName(brokerName.toUpperCase())
                .accessToken(tokens[0])
                .refreshToken(tokens.length > 1 ? tokens[1] : null)
                .tokenExpiry(Instant.now().plusSeconds(86400)) // 24 hours default
                .status("ACTIVE")
                .build();

        return repository.save(account);
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
