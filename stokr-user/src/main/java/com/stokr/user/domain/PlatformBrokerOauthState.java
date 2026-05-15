package com.stokr.user.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * OAuth CSRF state for platform market feed connect (not tied to a trader {@link BrokerOauthState}).
 */
@Getter
@Setter
@Entity
@Table(name = "platform_broker_oauth_states")
public class PlatformBrokerOauthState extends BaseEntity {

    @Column(name = "vendor_code", nullable = false, length = 32)
    private String vendorCode;

    @Column(name = "state_token", nullable = false, length = 128)
    private String stateToken;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed", nullable = false)
    private boolean consumed;
}
