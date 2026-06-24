package com.stokr.broker;

import com.stokr.config.SecurityUtils;
import com.stokr.marketdata.ZerodhaLiveDataScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/brokers")
@RequiredArgsConstructor
public class BrokerController {

    private final BrokerService brokerService;
    private final ZerodhaLiveDataScheduler zerodhaScheduler;

    @GetMapping
    public ResponseEntity<List<BrokerAccount>> getMyBrokers() {
        return ResponseEntity.ok(brokerService.getUserBrokers(SecurityUtils.currentUserId()));
    }

    @GetMapping("/supported")
    public ResponseEntity<List<String>> getSupportedBrokers() {
        return ResponseEntity.ok(brokerService.getSupportedBrokers());
    }

    @GetMapping("/{brokerName}/auth-url")
    public ResponseEntity<Map<String, String>> getAuthUrl(@PathVariable String brokerName) {
        return ResponseEntity.ok(Map.of("authUrl", brokerService.getAuthUrl(brokerName)));
    }

    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> disconnectBroker(@PathVariable Long accountId) {
        brokerService.disconnectBroker(accountId, SecurityUtils.currentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Health check for Zerodha broker connectivity.
     * Returns status: OK | NO_ACCOUNT | TOKEN_EXPIRED
     * Frontend polls this and blocks live trading when not OK.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getBrokerHealth() {
        String healthStatus = zerodhaScheduler.getHealthStatus();
        boolean isOk = "OK".equals(healthStatus);

        String message = switch (healthStatus) {
            case "NO_ACCOUNT"    -> "No Zerodha account connected. Connect Zerodha to enable live trading.";
            case "TOKEN_EXPIRED" -> "Zerodha session expired. Click 'Reconnect' to restore live trading.";
            default              -> "Zerodha connected and live data active.";
        };

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status",  healthStatus);
        resp.put("ok",      isOk);
        resp.put("message", message);
        resp.put("broker",  "ZERODHA");
        return ResponseEntity.ok(resp);
    }
}
