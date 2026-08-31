package com.stokr.arbitrage;

import com.stokr.broker.BrokerAccount;
import com.stokr.broker.BrokerAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultiTenantExecutionRouter {

    private final OptionArbAutoExecService autoExecService;
    private final BrokerAccountRepository brokerAccountRepo;

    /**
     * Safely routes manual/scheduled live trades for a specific user.
     * Prevents the legacy engine from executing trades on the wrong broker account.
     */
    public Map<String, Object> executeTradeForUser(OptionArbOpportunity opp, int lots, String broker, Long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        
        if (opp == null) {
            result.put("status", "ERROR");
            result.put("message", "Opportunity not found");
            return result;
        }

        // --- PAPER TRADES ---
        if ("PAPER".equalsIgnoreCase(broker)) {
            // OptionArbAutoExecService doesn't execute paper trades; they are just tracked in DB.
            // But if they are routed here by mistake, we fallback to AutoExecService's behavior.
            return autoExecService.manualExecuteLive(opp, lots, broker, userId);
        }

        // --- TRADER RISK LIMITS CHECK ---
        // TODO: We can wire in a TraderRiskLimitsRepository here in the future
        // if we want to enforce Max Daily Loss limits per trader.

        // --- MULTI-TENANT BROKER RESOLUTION ---
        // Verify the user actually has an active connection to the requested broker.
        // This prevents Trader A from trading on Admin's Zerodha account.
        List<BrokerAccount> userAccounts = brokerAccountRepo.findByUserIdAndBrokerNameAndStatus(userId, broker.toUpperCase(), "ACTIVE");
        
        if (userAccounts.isEmpty()) {
            log.warn("User {} attempted to trade on {} but has no active account", userId, broker);
            result.put("status", "ERROR");
            result.put("message", "No active " + broker + " account connected. Please link it in the Brokers tab.");
            return result;
        }

        log.info("Routing {} trade for user {} using account ID {}", broker, userId, userAccounts.get(0).getId());
        
        // Delegate to the legacy engine using the safely injected overloaded method
        return autoExecService.manualExecuteLive(opp, lots, broker, userId);
    }
}
