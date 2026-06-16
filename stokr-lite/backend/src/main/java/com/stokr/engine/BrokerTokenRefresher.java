package com.stokr.engine;

import com.stokr.broker.BrokerAccount;
import com.stokr.broker.BrokerAccountRepository;
import com.stokr.risk.ErrorLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerTokenRefresher {

    private final BrokerAccountRepository brokerAccountRepository;
    private final ErrorLogService errorLogService;

    /**
     * Run every hour to check for expiring broker tokens and refresh them.
     * Zerodha tokens expire daily, so we refresh proactively.
     */
    @Scheduled(cron = "0 0 8 * * MON-FRI")
    public void refreshExpiringTokens() {
        log.info("=== BROKER TOKEN REFRESH CHECK ===");

        List<BrokerAccount> activeAccounts = brokerAccountRepository.findByStatus("ACTIVE");
        Instant threshold = Instant.now().plusSeconds(3600); // Refresh if expiring within 1 hour

        for (BrokerAccount account : activeAccounts) {
            if (account.getTokenExpiry() != null && account.getTokenExpiry().isBefore(threshold)) {
                log.info("Token expiring soon for broker account {} ({})",
                        account.getId(), account.getBrokerName());
                try {
                    // TODO: Implement actual token refresh per broker
                    // Zerodha: Use refresh_token to get new access_token
                    // Dhan: Re-auth required
                    // Fyers: Use refresh_token
                    log.info("Token refresh for {} would happen here", account.getBrokerName());
                } catch (Exception e) {
                    log.error("Token refresh failed for broker account {}", account.getId(), e);
                    errorLogService.logError(null, "TOKEN_REFRESH_FAILED",
                            "Broker " + account.getBrokerName() + " account " + account.getId(),
                            e.getMessage(), "ERROR");
                }
            }
        }

        log.info("=== TOKEN REFRESH CHECK COMPLETE ===");
    }
}
