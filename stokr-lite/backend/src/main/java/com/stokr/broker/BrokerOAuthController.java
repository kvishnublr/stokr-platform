package com.stokr.broker;

import com.stokr.config.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/brokers")
@RequiredArgsConstructor
public class BrokerOAuthController {

    private final BrokerService brokerService;

    /**
     * OAuth callback endpoint - called by broker after user authorizes.
     * This endpoint is publicly accessible (configured in SecurityConfig).
     */
    @GetMapping("/{brokerName}/callback")
    public ResponseEntity<Map<String, Object>> handleCallback(
            @PathVariable String brokerName,
            @RequestParam("request_token") String requestToken) {
        Long userId = SecurityUtils.currentUserId();
        BrokerAccount account = brokerService.completeOAuth(userId, brokerName, requestToken);
        return ResponseEntity.ok(Map.of(
                "status", "connected",
                "brokerAccountId", account.getId(),
                "brokerName", account.getBrokerName()
        ));
    }
}
