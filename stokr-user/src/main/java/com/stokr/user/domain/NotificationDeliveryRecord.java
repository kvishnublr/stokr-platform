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
@Table(name = "notification_delivery_records")
public class NotificationDeliveryRecord extends BaseEntity {

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "channel", nullable = false, length = 32)
    private String channel;

    @Column(name = "template_key", nullable = false, length = 128)
    private String templateKey;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "payload_json", columnDefinition = "text")
    private String payloadJson;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;
}
