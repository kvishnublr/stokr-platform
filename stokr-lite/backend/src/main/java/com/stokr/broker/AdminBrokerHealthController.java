package com.stokr.broker;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminBrokerHealthController {

    private final BrokerAccountRepository brokerAccountRepository;

    public AdminBrokerHealthController(BrokerAccountRepository brokerAccountRepository) {
        this.brokerAccountRepository = brokerAccountRepository;
    }

    @GetMapping("/broker-health")
    public ResponseEntity<List<Map<String, Object>>> getBrokerHealth() {
        List<BrokerAccount> accounts = brokerAccountRepository.findAll();

        List<Map<String, Object>> result = accounts.stream().map(acc -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", acc.getId());
            entry.put("brokerName", acc.getBrokerName());
            entry.put("clientId", acc.getClientId());

            boolean tokenExpired = acc.getTokenExpiry() != null && Instant.now().isAfter(acc.getTokenExpiry());
            entry.put("status", tokenExpired ? "TOKEN_EXPIRED" : acc.getStatus());
            entry.put("tokenExpiry", acc.getTokenExpiry() != null ? acc.getTokenExpiry().toString() : null);
            entry.put("autoReconnect", acc.getAutoReconnect());
            entry.put("hasCredentials", acc.getZerodhaPassword() != null && !acc.getZerodhaPassword().isEmpty());
            entry.put("createdAt", acc.getCreatedAt() != null ? acc.getCreatedAt().toString() : null);
            return entry;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    @PutMapping("/broker-credentials")
    public ResponseEntity<Map<String, Object>> updateBrokerCredentials(@RequestBody Map<String, Object> body) {
        Long id = body.get("id") != null ? Long.valueOf(body.get("id").toString()) : null;
        if (id == null) return ResponseEntity.badRequest().body(Map.of("error", "id required"));

        Optional<BrokerAccount> opt = brokerAccountRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "broker account not found"));

        BrokerAccount acc = opt.get();
        if (body.containsKey("zerodhaPassword")) acc.setZerodhaPassword((String) body.get("zerodhaPassword"));
        if (body.containsKey("zerodhaTotpSecret")) acc.setZerodhaTotpSecret((String) body.get("zerodhaTotpSecret"));
        if (body.containsKey("autoReconnect")) acc.setAutoReconnect(Boolean.valueOf(body.get("autoReconnect").toString()));

        brokerAccountRepository.save(acc);
        return ResponseEntity.ok(Map.of("ok", true, "message", "Credentials updated"));
    }
}
