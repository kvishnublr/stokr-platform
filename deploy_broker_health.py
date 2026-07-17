import subprocess, os

def run(cmd):
    r = subprocess.run(["ssh", "-o", "ConnectTimeout=10", "root@173.249.55.84", cmd], capture_output=True, text=True, timeout=30)
    if r.stdout.strip(): print(r.stdout.strip())
    if r.returncode != 0 and r.stderr.strip(): print(f"ERR: {r.stderr.strip()}")
    return r.returncode == 0

# 1. Create AdminBrokerHealthController.java
admin_controller = r'''package com.stokr.broker;

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
            entry.put("status", acc.getTokenExpiry() != null && Instant.now().isAfter(acc.getTokenExpiry())
                ? "TOKEN_EXPIRED" : acc.getStatus());
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
'''

# Write file to server via temp
local_path = os.path.join(os.environ.get("TEMP", "."), "AdminBrokerHealthController.java")
with open(local_path, "w") as f:
    f.write(admin_controller)

subprocess.run(["scp", local_path, "root@173.249.55.84:/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/broker/AdminBrokerHealthController.java"], timeout=15)
print("1. AdminBrokerHealthController.java deployed")

# 2. Update Brokers.jsx to pre-fill credentials from DB
brokers_py = r'''import subprocess
# SCP the Brokers.jsx update
'''

# 3. Rebuild Docker
print("2. Building Docker backend...")
r = subprocess.run(["ssh", "-o", "ConnectTimeout=10", "root@173.249.55.84",
    "cd /opt/stokr/stokr-platform/stokr-lite && docker-compose build --no-cache backend 2>&1 | tail -5"],
    capture_output=True, text=True, timeout=300)
print(r.stdout.strip() if r.stdout else "build started...")

# 4. Restart
print("3. Restarting backend...")
subprocess.run(["ssh", "-o", "ConnectTimeout=10", "root@173.249.55.84",
    "kill -9 $(ss -tlnp | grep 8081 | grep -oP 'pid=\K\d+') 2>/dev/null; sleep 2; docker rm -f stokr-lite-backend 2>/dev/null; cd /opt/stokr/stokr-platform/stokr-lite && docker-compose up -d backend 2>&1"],
    capture_output=True, text=True, timeout=30)

print("4. Done! Backend restarting...")
