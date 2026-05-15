package com.stokr.bootstrap.feed.zerodha;

import com.stokr.user.domain.PlatformBrokerFeedSession;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PlatformZerodhaFeedTelemetryService {

    private final PlatformBrokerFeedSessionRepository sessionRepository;

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
        sessionRepository.save(s);
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
    }

    @Transactional
    public void markReconnectBump(String vendor) {
        PlatformBrokerFeedSession s = sessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse(vendor).orElse(null);
        if (s == null) {
            return;
        }
        s.setReconnectCount(s.getReconnectCount() + 1);
        sessionRepository.save(s);
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
            Integer tickProcessingLatencyMs
    ) {
    }
}
