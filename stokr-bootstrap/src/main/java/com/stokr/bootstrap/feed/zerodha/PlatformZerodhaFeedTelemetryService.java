package com.stokr.bootstrap.feed.zerodha;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.execution.safety.BrokerDisconnectProtectionService;
import com.stokr.marketdata.monitor.FeedHealthWebSocketState;
import com.stokr.user.domain.PlatformBrokerFeedSession;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformZerodhaFeedTelemetryService {

    private final PlatformBrokerFeedSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final FeedHealthWebSocketState feedHealthWebSocketState;
    private final ObjectProvider<BrokerDisconnectProtectionService> brokerDisconnectProtectionService;

    @Transactional
    public void saveWindow(String vendor, PlatformFeedWindowMetrics m) {
        PlatformBrokerFeedSession s = sessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse(vendor).orElse(null);
        if (s == null) {
            return;
        }
        s.setPacketsPerSec(m.packetsPerSec());
        s.setTicksPerSec(m.ticksPerSec());
        s.setSubscriptionCount(m.subscriptionCount());
        s.setWebsocketState(m.websocketState());
        s.setReconnecting(m.reconnecting());
        if (m.lastPacketAt() != null) {
            s.setLastPacketAt(m.lastPacketAt());
        }
        if (m.lastTickAt() != null) {
            s.setLastTickAt(m.lastTickAt());
        }
        if (m.lastHeartbeatAt() != null) {
            s.setLastHeartbeatAt(m.lastHeartbeatAt());
        }
        if (m.feedLagMs() != null) {
            s.setFeedLagMs(m.feedLagMs());
        }
        if (m.tickProcessingLatencyMs() != null) {
            s.setTickProcessingLatencyMs(m.tickProcessingLatencyMs());
        }
        s.setTelemetryJson(encodeTelemetryJson(m));
        sessionRepository.save(s);
        if ("OPEN".equalsIgnoreCase(m.websocketState())) {
            feedHealthWebSocketState.markConnected();
        } else if ("CLOSED".equalsIgnoreCase(m.websocketState())) {
            feedHealthWebSocketState.markDisconnected("WINDOW_CLOSED");
        }
    }

    private String encodeTelemetryJson(PlatformFeedWindowMetrics m) {
        Map<String, Object> tel = new LinkedHashMap<>();
        tel.put("capturedAt", Instant.now().toString());
        tel.put("packetsPerSec", m.packetsPerSec());
        tel.put("ticksPerSec", m.ticksPerSec());
        tel.put("subscriptionCount", m.subscriptionCount());
        tel.put("websocketState", m.websocketState());
        if (m.streamingSymbolsCsv() != null && !m.streamingSymbolsCsv().isBlank()) {
            tel.put("streamingSymbols", m.streamingSymbolsCsv());
        }
        try {
            return objectMapper.writeValueAsString(tel);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"telemetry_json_encode_failed\"}";
        }
    }

    @Transactional
    public void markWebsocketClosed(String vendor, String reason) {
        PlatformBrokerFeedSession s = sessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse(vendor).orElse(null);
        if (s == null) {
            return;
        }
        s.setWebsocketState("CLOSED");
        s.setReconnecting(false);
        s.setDisconnectReason(truncate(reason, 512));
        sessionRepository.save(s);
        feedHealthWebSocketState.markDisconnected(reason);
        brokerDisconnectProtectionService.ifAvailable(svc -> svc.onBrokerDisconnected(reason));
    }

    @Transactional
    public void markReconnectBump(String vendor) {
        PlatformBrokerFeedSession s = sessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse(vendor).orElse(null);
        if (s == null) {
            return;
        }
        s.setReconnectCount(s.getReconnectCount() + 1);
        sessionRepository.save(s);
        feedHealthWebSocketState.incrementReconnectAttempt();
    }

    @Transactional
    public void markWebsocketOpen(String vendor) {
        PlatformBrokerFeedSession s = sessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse(vendor).orElse(null);
        if (s != null) {
            s.setWebsocketState("OPEN");
            s.setReconnecting(false);
            sessionRepository.save(s);
        }
        int attempts = feedHealthWebSocketState.reconnectAttempts();
        feedHealthWebSocketState.markConnected();
        if (attempts > 0) {
            log.info("feed.health.websocket_reconnected vendor={} reconnectAttempts={}", vendor, attempts);
            brokerDisconnectProtectionService.ifAvailable(BrokerDisconnectProtectionService::onBrokerRecovered);
        }
    }

    @Transactional
    public void markConnecting(String vendor) {
        PlatformBrokerFeedSession s = sessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse(vendor).orElse(null);
        if (s == null) {
            return;
        }
        s.setReconnecting(true);
        s.setWebsocketState("CONNECTING");
        sessionRepository.save(s);
        feedHealthWebSocketState.markDisconnected("CONNECTING");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record PlatformFeedWindowMetrics(
            double packetsPerSec,
            double ticksPerSec,
            int subscriptionCount,
            String websocketState,
            boolean reconnecting,
            Instant lastPacketAt,
            Instant lastTickAt,
            Instant lastHeartbeatAt,
            Integer feedLagMs,
            Integer tickProcessingLatencyMs,
            String streamingSymbolsCsv
    ) {
    }
}
