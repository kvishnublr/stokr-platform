package com.stokr.user.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "broker_accounts")
public class BrokerAccount extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "vendor_code", nullable = false, length = 32)
    private String vendorCode;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "metadata_json", columnDefinition = "text")
    private String metadataJson;

    @Column(name = "broker_user_id", length = 64)
    private String brokerUserId;

    @Column(name = "access_token_enc", columnDefinition = "text")
    private String accessTokenEnc;

    @Column(name = "refresh_token_enc", columnDefinition = "text")
    private String refreshTokenEnc;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "margin_snapshot_json", columnDefinition = "text")
    private String marginSnapshotJson;

    @Column(name = "health_status", nullable = false, length = 32)
    private String healthStatus = "UNKNOWN";

    /** Server IP used for outbound Zerodha API calls. NULL = use default server IP. */
    @Column(name = "outbound_ip", length = 45)
    private String outboundIp;
}
