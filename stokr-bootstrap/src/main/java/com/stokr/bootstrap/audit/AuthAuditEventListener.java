package com.stokr.bootstrap.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.admin.domain.AuditLog;
import com.stokr.admin.repository.AuditLogRepository;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.events.auth.AuthAuditEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persists auth-related domain events into {@code audit_logs}.
 */
@Component
public class AuthAuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuthAuditEventListener.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuthAuditEventListener(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Async
    @EventListener
    public void onLoginSucceeded(AuthAuditEvents.LoginSucceeded e) {
        save(e.userId(), "AUTH_LOGIN_SUCCESS", "AuthUser", e.userId().toString(), Map.of("email", e.email()));
    }

    @EventListener
    public void onLoginFailed(AuthAuditEvents.LoginFailed e) {
        save(null, "AUTH_LOGIN_FAILED", "AuthUser", e.principal(), Map.of("principal", e.principal(), "reason", e.reason()));
    }

    @Async
    @EventListener
    public void onLogout(AuthAuditEvents.Logout e) {
        save(e.userId(), "AUTH_LOGOUT", "AuthUser", e.userId().toString(), Map.of());
    }

    @Async
    @EventListener
    public void onRefresh(AuthAuditEvents.RefreshRotated e) {
        save(e.userId(), "AUTH_REFRESH", "AuthUser", e.userId().toString(), Map.of());
    }

    @Async
    @EventListener
    public void onRegister(AuthAuditEvents.UserRegistered e) {
        save(e.userId(), "AUTH_REGISTER", "AuthUser", e.userId().toString(), Map.of("email", e.email()));
    }

    @Async
    @EventListener
    public void onPwResetReq(AuthAuditEvents.PasswordResetRequested e) {
        save(e.userId(), "AUTH_PASSWORD_RESET_REQUEST", "AuthUser", e.userId().toString(), Map.of("email", e.email()));
    }

    @Async
    @EventListener
    public void onPwResetDone(AuthAuditEvents.PasswordResetCompleted e) {
        save(e.userId(), "AUTH_PASSWORD_RESET_COMPLETE", "AuthUser", e.userId().toString(),
                Map.of("selfService", e.selfService()));
    }

    @Async
    @EventListener
    public void onAccountStatus(AuthAuditEvents.AccountStatusChanged e) {
        save(e.actorUserId(), "AUTH_ACCOUNT_STATUS", "AuthUser", e.targetUserId().toString(),
                Map.of("enabled", e.enabled(), "targetUserId", e.targetUserId().toString()));
    }

    @Async
    @EventListener
    public void onEmailVerifyPlaceholder(AuthAuditEvents.EmailVerificationRequested e) {
        save(e.userId(), "AUTH_EMAIL_VERIFY_REQUEST_PLACEHOLDER", "AuthUser", e.userId().toString(), Map.of());
    }

    private void save(UUID actorUserId, String action, String resourceType, String resourceId, Map<String, ?> payload) {
        AuditLog al = new AuditLog();
        al.setActorUserId(actorUserId);
        al.setAction(action);
        al.setResourceType(resourceType);
        al.setResourceId(resourceId);
        al.setCorrelationId(CorrelationIdHolder.get());
        try {
            al.setPayloadJson(objectMapper.writeValueAsString(new LinkedHashMap<>(payload)));
        } catch (JsonProcessingException ex) {
            log.warn("audit payload {}", ex.toString());
            al.setPayloadJson("{}");
        }
        auditLogRepository.save(al);
    }
}
