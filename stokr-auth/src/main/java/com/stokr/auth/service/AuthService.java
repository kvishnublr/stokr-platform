package com.stokr.auth.service;

import com.stokr.auth.config.AuthLoginPolicyProperties;
import com.stokr.auth.domain.AuthEmailVerificationToken;
import com.stokr.auth.domain.AuthPasswordResetToken;
import com.stokr.auth.domain.AuthRefreshToken;
import com.stokr.auth.domain.AuthRole;
import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.dto.AuthResponse;
import com.stokr.auth.dto.ResendVerificationResponse;
import com.stokr.auth.dto.LoginRequest;
import com.stokr.auth.dto.RefreshRequest;
import com.stokr.auth.dto.RegisterRequest;
import com.stokr.auth.dto.ResetPasswordRequest;
import com.stokr.auth.mail.VerificationEmailDeliveryService;
import com.stokr.auth.mail.VerificationEmailSendOutcome;
import com.stokr.auth.jwt.JwtService;
import com.stokr.auth.repository.AuthEmailVerificationTokenRepository;
import com.stokr.auth.repository.AuthPasswordResetTokenRepository;
import com.stokr.auth.repository.AuthRefreshTokenRepository;
import com.stokr.auth.repository.AuthRoleRepository;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.auth.security.TokenBlacklistService;
import com.stokr.common.events.auth.AuthAuditEvents;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.ConflictException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.common.exception.UnauthorizedException;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    /** Canonical self-service signup role (see Flyway V1/V11). */
    private static final String ROLE_TRADER = "ROLE_TRADER";
    /** Legacy alias — migration V11 maps rows to ROLE_TRADER; kept as fallback if Flyway not applied yet. */
    private static final String ROLE_USER_LEGACY = "ROLE_USER";

    private final AuthUserRepository userRepository;
    private final AuthRoleRepository roleRepository;
    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final AuthPasswordResetTokenRepository passwordResetTokenRepository;
    private final AuthEmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthLoginPolicyProperties loginPolicy;
    private final VerificationEmailDeliveryService verificationEmailDeliveryService;
    private final Environment environment;

    @Value("${stokr.auth.email-verification-base-url:http://localhost:5173}")
    private String emailVerificationBaseUrl;

    @Value("${stokr.auth.verification-resend-cooldown-seconds:60}")
    private int verificationResendCooldownSeconds;

    @Value("${stokr.security.refresh-ttl-seconds:1209600}")
    private long refreshTtlSeconds;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new BadRequestException("Passwords do not match");
        }
        if (userRepository.existsByEmailIgnoreCaseAndDeletedFalse(request.email())) {
            throw new ConflictException("Email already registered");
        }
        String normalizedUsername = request.username().trim().toLowerCase();
        if (userRepository.existsByUsernameIgnoreCaseAndDeletedFalse(normalizedUsername)) {
            throw new ConflictException("Username already taken");
        }
        AuthRole traderRole = roleRepository.findByNameAndDeletedFalse(ROLE_TRADER)
                .orElseGet(() -> roleRepository.findByNameAndDeletedFalse(ROLE_USER_LEGACY)
                        .orElseThrow(() -> new ConflictException("ROLE_TRADER is not configured")));

        AuthUser user = new AuthUser();
        user.setUsername(normalizedUsername);
        user.setEmail(request.email().trim().toLowerCase());
        user.setDisplayName(request.displayName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEnabled(true);
        user.setEmailVerified(false);
        user.setTelegramVerified(false);
        user.setWhatsappVerified(false);
        user.setOnboardingComplete(false);
        user.setLiveTradingApproved(false);
        user.setMobilePhone(blankToNull(request.mobilePhone()));
        user.setTelegramUsername(blankToNull(request.telegramUsername()));
        user.setWhatsappE164(blankToNull(request.whatsAppNumber()));
        user.getRoles().add(traderRole);

        userRepository.saveAndFlush(user);
        AuthUser persisted = userRepository
                .findByEmailIgnoreCaseAndDeletedFalse(user.getEmail())
                .orElseThrow(() -> new IllegalStateException("User not found after registration"));
        eventPublisher.publishEvent(new AuthAuditEvents.UserRegistered(persisted.getId(), persisted.getEmail(), Instant.now()));
        VerificationEmailSendOutcome emailOutcome = issueEmailVerificationToken(persisted, false);
        return issueTokens(persisted, emailOutcome.name());
    }

    @Transactional
    public AuthResponse verifyEmail(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BadRequestException("Verification token required");
        }
        AuthEmailVerificationToken token = emailVerificationTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(sha256Hex(rawToken.trim()), Instant.now())
                .orElseThrow(() -> new BadRequestException("Invalid or expired verification link"));
        token.setUsed(true);
        emailVerificationTokenRepository.save(token);
        AuthUser user = token.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);
        eventPublisher.publishEvent(new AuthAuditEvents.EmailVerified(user.getId(), user.getEmail(), Instant.now()));
        return issueTokens(user, null);
    }

    /**
     * Authenticated user requests a fresh verification email.
     */
    @Transactional
    public ResendVerificationResponse resendEmailVerification(UUID userId) {
        AuthUser user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (user.isEmailVerified()) {
            throw new BadRequestException("Email already verified");
        }
        VerificationEmailSendOutcome outcome = issueEmailVerificationToken(user, true);
        return new ResendVerificationResponse(outcome.name());
    }

    private VerificationEmailSendOutcome issueEmailVerificationToken(AuthUser user, boolean enforceResendCooldown) {
        if (enforceResendCooldown && verificationResendCooldownSeconds > 0) {
            emailVerificationTokenRepository.findFirstByUser_IdAndDeletedFalseOrderByCreatedAtDesc(user.getId())
                    .ifPresent(prev -> {
                        Instant nextAllowed = prev.getCreatedAt().plusSeconds(verificationResendCooldownSeconds);
                        if (nextAllowed.isAfter(Instant.now())) {
                            throw new BadRequestException("Please wait before requesting another verification email.");
                        }
                    });
        }
        String raw = generateRawSecret();
        AuthEmailVerificationToken t = new AuthEmailVerificationToken();
        t.setUser(user);
        t.setTokenHash(sha256Hex(raw));
        t.setExpiresAt(Instant.now().plus(loginPolicy.getEmailVerificationHoursValid(), ChronoUnit.HOURS));
        t.setUsed(false);
        emailVerificationTokenRepository.save(t);
        eventPublisher.publishEvent(new AuthAuditEvents.EmailVerificationRequested(user.getId(), Instant.now()));
        String base = emailVerificationBaseUrl.replaceAll("/+$", "");
        String link = base + "/verify-email?token=" + URLEncoder.encode(raw, StandardCharsets.UTF_8);
        VerificationEmailSendOutcome outcome = verificationEmailDeliveryService.sendVerificationEmail(
                user.getEmail(),
                link,
                loginPolicy.getEmailVerificationHoursValid());
        if (outcome == VerificationEmailSendOutcome.NOT_CONFIGURED) {
            log.warn(
                    "SMTP not configured (spring.mail.host is blank). Use verification URL from logs for {}.",
                    user.getEmail());
            log.warn(
                    "Outbound email is not configured (set spring.mail.* to send). Verify {} using: {}",
                    user.getEmail(),
                    link);
        } else if (outcome == VerificationEmailSendOutcome.SEND_FAILED) {
            log.warn(
                    "Verification email send failed for {}. Token is valid until expiry; user may resend after cooldown.",
                    user.getEmail());
        }
        return outcome;
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        AuthUser user = findForAuthentication(request.principal());
        Instant now = Instant.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            eventPublisher.publishEvent(new AuthAuditEvents.LoginFailed(request.principal(), "locked", now));
            throw new UnauthorizedException("Account temporarily locked — try again later.");
        }
        if (!user.isEnabled() || user.isDeleted()) {
            eventPublisher.publishEvent(new AuthAuditEvents.LoginFailed(request.principal(), "disabled", now));
            throw new UnauthorizedException("Invalid credentials");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            handleFailedPassword(user, now);
            eventPublisher.publishEvent(new AuthAuditEvents.LoginFailed(request.principal(), "bad_password", now));
            throw new UnauthorizedException("Invalid credentials");
        }
        clearFailures(user);
        user.setLastLoginAt(now);
        userRepository.save(user);
        eventPublisher.publishEvent(new AuthAuditEvents.LoginSucceeded(user.getId(), user.getEmail(), now));
        return issueTokens(user, null);
    }

    private void handleFailedPassword(AuthUser user, Instant now) {
        int n = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(n);
        if (n >= loginPolicy.getMaxFailedAttempts()) {
            user.setLockedUntil(now.plus(loginPolicy.getLockDurationMinutes(), ChronoUnit.MINUTES));
        }
        userRepository.save(user);
    }

    private void clearFailures(AuthUser user) {
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        String hash = sha256Hex(request.refreshToken());
        AuthRefreshToken stored = refreshTokenRepository
                .findByTokenHashAndDeletedFalse(hash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        if (stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        try {
            stored.setRevoked(true);
            refreshTokenRepository.save(stored);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
            // Another request already revoked this token concurrently; token is consumed.
            log.debug("Concurrent refresh detected; token already rotated");
            throw new UnauthorizedException("Invalid refresh token");
        }
        AuthUser user = stored.getUser();
        eventPublisher.publishEvent(new AuthAuditEvents.RefreshRotated(user.getId(), Instant.now()));
        return issueTokens(user, null);
    }

    @Transactional
    public void logout(String accessToken, String refreshTokenRaw) {
        if (accessToken != null && !accessToken.isBlank()) {
            try {
                Claims c = jwtService.parse(accessToken);
                UUID uid = UUID.fromString(c.getSubject());
                eventPublisher.publishEvent(new AuthAuditEvents.Logout(uid, Instant.now()));
                tokenBlacklistService.denyToken(accessToken, c.getExpiration());
            } catch (Exception ignored) {
                // best-effort
            }
        }
        if (refreshTokenRaw != null && !refreshTokenRaw.isBlank()) {
            String hash = sha256Hex(refreshTokenRaw);
            refreshTokenRepository.findByTokenHashAndDeletedFalse(hash).ifPresent(rt -> {
                rt.setRevoked(true);
                refreshTokenRepository.save(rt);
            });
        }
    }

    /**
     * Always succeeds from API perspective to avoid email enumeration.
     */
    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmailIgnoreCaseAndDeletedFalse(email.trim().toLowerCase()).ifPresent(user -> {
            String raw = generateRawSecret();
            AuthPasswordResetToken t = new AuthPasswordResetToken();
            t.setUser(user);
            t.setTokenHash(sha256Hex(raw));
            t.setExpiresAt(Instant.now().plus(loginPolicy.getPasswordResetHoursValid(), ChronoUnit.HOURS));
            t.setUsed(false);
            passwordResetTokenRepository.save(t);
            eventPublisher.publishEvent(new AuthAuditEvents.PasswordResetRequested(user.getId(), user.getEmail(), Instant.now()));
            if (environment.acceptsProfiles(Profiles.of("dev", "local"))) {
                log.warn("[DEV ONLY] Password reset token for {} — paste as reset token: {}", user.getEmail(), raw);
            }
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new BadRequestException("Passwords do not match");
        }
        AuthPasswordResetToken token = passwordResetTokenRepository
                .findByTokenHashAndUsedFalseAndExpiresAtAfter(sha256Hex(request.token()), Instant.now())
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired reset token"));
        token.setUsed(true);
        passwordResetTokenRepository.save(token);
        AuthUser user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        clearFailures(user);
        userRepository.save(user);
        eventPublisher.publishEvent(new AuthAuditEvents.PasswordResetCompleted(user.getId(), true, Instant.now()));
    }

    private AuthUser findForAuthentication(String principal) {
        String p = principal.trim();
        if (p.contains("@")) {
            return userRepository
                    .findByEmailIgnoreCaseAndDeletedFalse(p.toLowerCase())
                    .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
        }
        return userRepository
                .findByUsernameIgnoreCaseAndDeletedFalse(p.toLowerCase())
                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
    }

    private AuthResponse issueTokens(AuthUser user, String verificationEmailStatus) {
        var roles = user.getRoles();
        Set<String> roleNames = (roles == null ? Collections.<AuthRole>emptySet() : roles).stream()
                .map(AuthRole::getName)
                .collect(Collectors.toSet());
        String scope = String.join(" ", roleNames);
        String access = jwtService.createAccessToken(user.getId(), user.getEmail(), scope);
        String refreshRaw = generateRefreshToken();
        String refreshHash = sha256Hex(refreshRaw);

        Instant exp = Instant.now().plusSeconds(refreshTtlSeconds);
        AuthRefreshToken rt = new AuthRefreshToken();
        rt.setUser(user);
        rt.setTokenHash(refreshHash);
        rt.setExpiresAt(exp);
        refreshTokenRepository.save(rt);

        io.jsonwebtoken.Claims claims = jwtService.parse(access);
        long expiresInSeconds = Math.max(0L,
                (claims.getExpiration().getTime() - Instant.now().toEpochMilli()) / 1000L);

        return new AuthResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                roleNames,
                user.isEmailVerified(),
                user.isTelegramVerified(),
                user.isWhatsappVerified(),
                user.isOnboardingComplete(),
                user.isLiveTradingApproved(),
                access,
                refreshRaw,
                expiresInSeconds,
                verificationEmailStatus
        );
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String generateRefreshToken() {
        byte[] buf = new byte[48];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** Raw secret shown only in dev logs for forgot-password flow until email provider is wired. */
    private static String generateRawSecret() {
        byte[] buf = new byte[36];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
