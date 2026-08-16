package com.stokr.broker;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "broker_accounts")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BrokerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "broker_name", nullable = false)
    private String brokerName;

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "access_token", columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    @Column(name = "token_expiry")
    private Instant tokenExpiry;

    @Column(nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "zerodha_password", columnDefinition = "TEXT")
    private String zerodhaPassword;

    @Column(name = "zerodha_totp_secret", columnDefinition = "TEXT")
    private String zerodhaTotpSecret;

    @Column(name = "navia_api_key", columnDefinition = "TEXT")
    private String naviaApiKey;

    @Column(name = "navia_api_secret", columnDefinition = "TEXT")
    private String naviaApiSecret;

    @Column(name = "mofsl_password", columnDefinition = "TEXT")
    private String mofslPassword;

    @Column(name = "mofsl_totp_secret", columnDefinition = "TEXT")
    private String mofslTotpSecret;

    @Column(name = "auto_reconnect")
    @Builder.Default
    private Boolean autoReconnect = false;

    @Column(name = "last_auto_reconnect")
    private Instant lastAutoReconnect;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isTokenExpired() {
        return tokenExpiry != null && Instant.now().isAfter(tokenExpiry);
    }
}
