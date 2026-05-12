package com.stokr.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.admin.domain.AuditLog;
import com.stokr.admin.dto.AdminPasswordResetResponse;
import com.stokr.admin.dto.AdminUserDetailDto;
import com.stokr.admin.dto.AdminUserSummaryDto;
import com.stokr.admin.dto.UserStatusPatchRequest;
import com.stokr.admin.repository.AuditLogRepository;
import com.stokr.admin.spec.AdminUserSpecifications;
import com.stokr.auth.domain.AuthRole;
import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.events.auth.AuthAuditEvents;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.user.repository.BrokerAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthUserRepository userRepository;
    private final StrategyInstanceRepository strategyInstanceRepository;
    private final BrokerAccountRepository brokerAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Page<AdminUserSummaryDto> search(
            Pageable pageable,
            String search,
            Boolean enabled,
            String role,
            Instant registeredFrom,
            Instant registeredTo
    ) {
        Specification<AuthUser> spec =
                AdminUserSpecifications.filter(search, enabled, role, registeredFrom, registeredTo);
        Page<AuthUser> userPage = userRepository.findAll(spec, pageable);
        return userPage.map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailDto get(UUID id) {
        AuthUser u = userRepository.findById(id).filter(x -> !x.isDeleted()).orElseThrow(() -> new NotFoundException("User not found"));
        return toDetail(u);
    }

    @Transactional
    public AdminUserDetailDto patchStatus(UUID id, UserStatusPatchRequest body, UUID actorId) {
        AuthUser u = userRepository.findById(id).filter(x -> !x.isDeleted()).orElseThrow(() -> new NotFoundException("User not found"));
        if (!body.enabled() && u.getId().equals(actorId)) {
            throw new BadRequestException("You cannot disable your own account");
        }
        u.setEnabled(body.enabled());
        userRepository.save(u);
        eventPublisher.publishEvent(new AuthAuditEvents.AccountStatusChanged(id, body.enabled(), actorId, Instant.now()));
        return toDetail(u);
    }

    @Transactional
    public AdminPasswordResetResponse resetPassword(UUID id, UUID actorId) {
        AuthUser u = userRepository.findById(id).filter(x -> !x.isDeleted()).orElseThrow(() -> new NotFoundException("User not found"));
        String temp = randomPassword();
        u.setPasswordHash(passwordEncoder.encode(temp));
        userRepository.save(u);
        audit(actorId, "USER_PASSWORD_RESET", "AuthUser", id.toString(), Map.of("userId", id.toString()));
        return new AdminPasswordResetResponse(temp);
    }

    private AdminUserSummaryDto toSummary(AuthUser u) {
        UUID id = u.getId();
        long strat = strategyInstanceRepository.countByUserIdAndEnabledTrueAndDeletedFalse(id);
        boolean broker = brokerAccountRepository.existsByUserIdAndDeletedFalse(id);
        return new AdminUserSummaryDto(
                id,
                u.getUsername(),
                u.getEmail(),
                u.getDisplayName(),
                u.isEnabled(),
                roleNames(u),
                u.getCreatedAt(),
                u.getLastLoginAt(),
                strat,
                broker
        );
    }

    private AdminUserDetailDto toDetail(AuthUser u) {
        UUID id = u.getId();
        long strat = strategyInstanceRepository.countByUserIdAndEnabledTrueAndDeletedFalse(id);
        boolean broker = brokerAccountRepository.existsByUserIdAndDeletedFalse(id);
        List<String> keys = strategyInstanceRepository.findAllForUserWithDefinition(id).stream()
                .filter(StrategyInstance::isEnabled)
                .map(si -> si.getDefinition().getStrategyKey())
                .collect(Collectors.toList());
        return new AdminUserDetailDto(
                id,
                u.getUsername(),
                u.getEmail(),
                u.getDisplayName(),
                u.isEnabled(),
                roleNames(u),
                u.getCreatedAt(),
                u.getLastLoginAt(),
                strat,
                broker,
                keys,
                u.isEmailVerified(),
                u.isOnboardingComplete(),
                u.isLiveTradingApproved()
        );
    }

    @Transactional
    public AdminUserDetailDto patchLiveTradingApproval(UUID id, boolean approved, UUID actorId) {
        AuthUser u = userRepository.findById(id).filter(x -> !x.isDeleted()).orElseThrow(() -> new NotFoundException("User not found"));
        u.setLiveTradingApproved(approved);
        userRepository.save(u);
        audit(actorId, "LIVE_TRADING_APPROVAL", "AuthUser", id.toString(),
                Map.of("userId", id.toString(), "liveTradingApproved", approved));
        return toDetail(u);
    }

    private static Set<String> roleNames(AuthUser u) {
        if (u.getRoles() == null || u.getRoles().isEmpty()) {
            return Set.of();
        }
        return u.getRoles().stream()
                .filter(Objects::nonNull)
                .map(AuthRole::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private void audit(UUID actorId, String action, String resourceType, String resourceId, Map<String, ?> payload) {
        AuditLog log = new AuditLog();
        log.setActorUserId(actorId);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setCorrelationId(CorrelationIdHolder.get());
        try {
            log.setPayloadJson(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.setPayloadJson("{}");
        }
        auditLogRepository.save(log);
    }

    private static String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        sb.append("!7Yz");
        return sb.toString();
    }
}
