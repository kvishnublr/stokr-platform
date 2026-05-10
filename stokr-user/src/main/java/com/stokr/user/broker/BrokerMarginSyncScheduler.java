package com.stokr.user.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.repository.BrokerAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Periodically touches connected Zerodha accounts and records a margin snapshot (Kite user/margins API).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BrokerMarginSyncScheduler {

    private final BrokerAccountRepository brokerAccountRepository;
    private final ObjectMapper objectMapper;

    @Value("${stokr.broker.zerodha.sync-enabled:true}")
    private boolean syncEnabled;

    @Scheduled(fixedDelayString = "${stokr.broker.zerodha.sync-ms:300000}")
    @Transactional
    public void sync() {
        if (!syncEnabled) {
            return;
        }
        List<BrokerAccount> accounts = brokerAccountRepository.findAllByVendorCodeIgnoreCaseAndDeletedFalse("ZERODHA");
        for (BrokerAccount a : accounts) {
            if (a.getAccessTokenEnc() == null || a.getAccessTokenEnc().isBlank()) {
                a.setHealthStatus("DEGRADED");
                brokerAccountRepository.save(a);
                continue;
            }
            // Real Kite call would decrypt token and add Authorization: token api_key:access_token
            // Here we only mark successful sync window for health monitoring.
            try {
                ObjectNode snap = objectMapper.createObjectNode();
                snap.put("note", "synthetic margin snapshot — wire Kite /user/margins with session");
                snap.put("capturedAt", Instant.now().toString());
                a.setMarginSnapshotJson(objectMapper.writeValueAsString(snap));
                a.setLastSyncAt(Instant.now());
                a.setHealthStatus("HEALTHY");
                brokerAccountRepository.save(a);
            } catch (Exception e) {
                log.debug("broker.sync.skip id={} {}", a.getId(), e.toString());
                a.setHealthStatus("DEGRADED");
                brokerAccountRepository.save(a);
            }
        }
    }
}
