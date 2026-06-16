package com.stokr.broker;

import com.stokr.config.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/brokers")
@RequiredArgsConstructor
public class BrokerController {

    private final BrokerService brokerService;

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
}
