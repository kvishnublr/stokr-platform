package com.stokr.auth.domain;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(name = "auth_users")
public class AuthUser extends BaseEntity {

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "email", nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "mobile_phone", length = 32)
    private String mobilePhone;

    @Column(name = "telegram_username", length = 64)
    private String telegramUsername;

    /** OAuth/deep-link binding with Telegram bot — populated after verification. */
    @Column(name = "telegram_chat_id", length = 64)
    private String telegramChatId;

    @Column(name = "whatsapp_e164", length = 32)
    private String whatsappE164;

    @Column(name = "telegram_verified", nullable = false)
    private boolean telegramVerified;

    @Column(name = "whatsapp_verified", nullable = false)
    private boolean whatsappVerified;

    @Column(name = "onboarding_complete", nullable = false)
    private boolean onboardingComplete;

    /** Admin gate for routing real broker orders (paper/sim allowed when false). */
    @Column(name = "live_trading_approved", nullable = false)
    private boolean liveTradingApproved;

    /**
     * LAZY: eager fetch breaks {@code Page}+Specification pagination (admin user list).
     * Batch loading avoids N+1 when mapping summaries inside {@code @Transactional}.
     */
    @BatchSize(size = 32)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "auth_user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<AuthRole> roles = new HashSet<>();
}
