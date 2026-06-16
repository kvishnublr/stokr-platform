package com.stokr.admin;

import com.stokr.broker.BrokerAccount;
import com.stokr.broker.BrokerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminBrokerHealthService {

    private final BrokerService brokerService;

    public List<Map<String, Object>> getBrokerHealthStatus() {
        List<BrokerAccount> allActive = brokerService.getAllActiveAccounts();

        return allActive.stream()
                .map(account -> Map.<String, Object>of(
                        "id", account.getId(),
                        "userId", account.getUserId(),
                        "brokerName", account.getBrokerName(),
                        "status", account.getStatus(),
                        "tokenExpired", account.isTokenExpired(),
                        "tokenExpiry", account.getTokenExpiry() != null
                                ? account.getTokenExpiry().toString() : "N/A"
                ))
                .collect(Collectors.toList());
    }

    public Map<String, Long> getBrokerStats() {
        List<BrokerAccount> allActive = brokerService.getAllActiveAccounts();
        long healthy = allActive.stream().filter(a -> !a.isTokenExpired()).count();
        long expired = allActive.stream().filter(BrokerAccount::isTokenExpired).count();

        return Map.of(
                "totalActive", (long) allActive.size(),
                "healthy", healthy,
                "tokenExpired", expired
        );
    }
}
