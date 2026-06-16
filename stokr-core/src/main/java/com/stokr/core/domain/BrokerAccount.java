package com.stokr.core.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "broker_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrokerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "vendor_code", nullable = false)
    @Builder.Default
    private String vendorCode = "ZERODHA";

    @Column(name = "access_token_encrypted")
    private String accessTokenEncrypted;

    @Column(name = "api_key")
    private String apiKey;

    @Column(name = "api_secret_encrypted")
    private String apiSecretEncrypted;

    @Column(name = "request_token_encrypted")
    private String requestTokenEncrypted;

    @Column(name = "access_token_expiry")
    private Instant accessTokenExpiry;

    @Column(name = "account_number")
    private String accountNumber;

    @Column(name = "account_name")
    private String accountName;

    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted")
    @Builder.Default
    private Boolean deleted = false;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public boolean isTokenValid() {
        if (accessTokenExpiry == null) return false;
        return Instant.now().isBefore(accessTokenExpiry);
    }

    public boolean isExpired() {
        if (accessTokenExpiry == null) return true;
        return Instant.now().isAfter(accessTokenExpiry);
    }
}
