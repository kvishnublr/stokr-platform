package com.stokr.user.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Admin-owned platform market feed session (ingestion plane), separate from {@link BrokerAccount} trader execution OAuth.
 */
@Getter
@Setter
@Entity
@Table(name = "platform_broker_feed_sessions")
public class PlatformBrokerFeedSession extends BaseEntity {

    @Column(name = "vendor_code", nullable = false, length = 32)
    private String vendorCode;

    @Column(name = "connection_state", nullable = false, length = 32)
    private String connectionState;

    @Column(name = "websocket_state", nullable = false, length = 32)
    private String websocketState = "CLOSED";

    @Column(name = "access_token_enc", columnDefinition = "text")
    private String accessTokenEnc;

    @Column(name = "refresh_token_enc", columnDefinition = "text")
    private String refreshTokenEnc;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "last_tick_at")
    private Instant lastTickAt;

    @Column(name = "last_packet_at")
    private Instant lastPacketAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "reconnect_count", nullable = false)
    private int reconnectCount;

    @Column(name = "subscription_count", nullable = false)
    private int subscriptionCount;

    @Column(name = "packets_per_sec")
    private Double packetsPerSec;

    @Column(name = "ticks_per_sec")
    private Double ticksPerSec;

    @Column(name = "feed_lag_ms")
    private Integer feedLagMs;

    @Column(name = "disconnect_reason", length = 512)
    private String disconnectReason;

    @Column(name = "reconnecting", nullable = false)
    private boolean reconnecting;

    @Column(name = "tick_processing_latency_ms")
    private Integer tickProcessingLatencyMs;

    @Column(name = "ingestion_paused", nullable = false)
    private boolean ingestionPaused;

    @Column(name = "instrument_sync_state", length = 64)
    private String instrumentSyncState;

    @Column(name = "telemetry_json", columnDefinition = "text")
    private String telemetryJson;
}
