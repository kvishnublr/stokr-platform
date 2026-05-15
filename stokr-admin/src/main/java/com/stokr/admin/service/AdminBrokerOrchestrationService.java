package com.stokr.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stokr.admin.audit.OperationalHistoryService;
import com.stokr.common.exception.BadRequestException;
import com.stokr.user.broker.ZerodhaBrokerOperationsService;
import com.stokr.user.domain.BrokerAccount;
import com.stokr.user.repository.BrokerAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-plane broker orchestration (acts on behalf of traders — audit via existing broker audit events).
 */
@Service
@RequiredArgsConstructor
public class AdminBrokerOrchestrationService {

    private final ZerodhaBrokerOperationsService zerodhaBrokerOperationsService;
    private final BrokerAccountRepository brokerAccountRepository;
    private final ObjectMapper objectMapper;
    private final OperationalHistoryService operationalHistoryService;

    @Transactional
    public Map<String, Object> zerodhaTestSession(UUID userId) {
        ZerodhaBrokerOperationsService.BrokerTestConnectionDto dto = zerodhaBrokerOperationsService.testConnection(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vendor", "ZERODHA");
        out.put("ok", dto.ok());
        out.put("message", dto.message());
        out.put("profileUserName", dto.profileUserName());
        out.put("marginSummary", dto.marginSummary());
        operationalHistoryService.record("BROKER_ZERODHA_TEST_SESSION", out, "ADMIN", userId);
        return out;
    }

    @Transactional
    public Map<String, Object> zerodhaDisconnect(UUID userId) {
        zerodhaBrokerOperationsService.disconnect(userId);
        operationalHistoryService.record("BROKER_ZERODHA_DISCONNECT", Map.of("userId", userId.toString()), "ADMIN", userId);
        return Map.of("vendor", "ZERODHA", "disconnected", true);
    }

    @Transactional
    public Map<String, Object> setFeedPaused(UUID userId, String vendor, boolean paused) {
        String v = vendor == null ? "" : vendor.trim().toUpperCase();
        BrokerAccount account = brokerAccountRepository
                .findFirstByUserIdAndVendorCodeIgnoreCaseAndDeletedFalseOrderByUpdatedAtDesc(userId, v)
                .orElseThrow(() -> new BadRequestException("No broker account for user/vendor"));
        ObjectNode meta = readMeta(account.getMetadataJson());
        meta.put("opsFeedPaused", paused);
        account.setMetadataJson(meta.toString());
        brokerAccountRepository.save(account);
        Map<String, Object> res = Map.of("vendor", v, "userId", userId.toString(), "opsFeedPaused", paused);
        operationalHistoryService.record("BROKER_FEED_PAUSE", res, "ADMIN", userId);
        return res;
    }

    private ObjectNode readMeta(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode n = objectMapper.readTree(json);
            if (n instanceof ObjectNode on) {
                return on;
            }
            ObjectNode wrap = objectMapper.createObjectNode();
            wrap.set("_raw", n);
            return wrap;
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
