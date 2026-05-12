package com.stokr.user.broker;

import com.stokr.user.domain.BrokerAccount;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Derives broker health from persisted account state (no external calls).
 */
@Service
public class ZerodhaBrokerHealthService {

    public String deriveHealth(BrokerAccount account, boolean tokenValid) {
        if (account == null) {
            return "UNKNOWN";
        }
        if (!tokenValid) {
            return "DEGRADED";
        }
        String hs = account.getHealthStatus();
        return hs != null && !hs.isBlank() ? hs : "UNKNOWN";
    }

    public boolean tokenLooksValid(BrokerAccount account) {
        if (account.getAccessTokenEnc() == null || account.getAccessTokenEnc().isBlank()) {
            return false;
        }
        Instant exp = account.getTokenExpiresAt();
        return exp == null || exp.isAfter(Instant.now());
    }
}
