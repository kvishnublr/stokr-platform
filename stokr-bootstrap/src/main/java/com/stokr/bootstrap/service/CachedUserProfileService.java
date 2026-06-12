package com.stokr.bootstrap.service;

import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.user.broker.ZerodhaBrokerOperationsService;
import com.stokr.user.dto.UserProfileDto;
import com.stokr.user.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Release_v2 Phase 1 Optimization: Cached User Profile Service
 *
 * @since Release_v2
 */
@Slf4j
@Service
@Profile("v2")
@RequiredArgsConstructor
public class CachedUserProfileService {

    private final AuthUserRepository authUserRepository;
    private final UserProfileService userProfileService;
    private final ZerodhaBrokerOperationsService zerodhaBrokerOperationsService;

    @Value("${stokr.oms.exposure.max-daily-loss:50000}")
    private BigDecimal defaultDailyLossLimit;

    @Value("${stokr.risk.max-order-notional:50000000}")
    private BigDecimal defaultMaxOrderNotional;

    @Value("${stokr.risk.max-open-positions:100}")
    private int defaultMaxOpenPositions;

    @Value("${stokr.oms.exposure.max-concurrent-positions:10}")
    private int defaultMaxConcurrentOrders;

    public static class UserProfile {
        public UUID userId;
        public String email;
        public String name;
        public String brokerVendor;
        public boolean brokerConnected;
        public long createdAt;
        public long lastLoginAt;
        public boolean riskLimitsEnabled;
        public BigDecimal dailyLossLimit;
    }

    @Cacheable(value = "user_profile", key = "#userId", unless = "#result == null")
    @Transactional(readOnly = true)
    public UserProfile getProfile(UUID userId) {
        log.debug("Fetching user profile for userId: {}", userId);

        AuthUser authUser = authUserRepository.findById(userId).orElse(null);
        if (authUser == null || authUser.isDeleted()) {
            return null;
        }

        UserProfileDto profileDto = userProfileService.ensureProfile(userId);
        var brokerStatus = zerodhaBrokerOperationsService.status(userId);

        UserProfile profile = new UserProfile();
        profile.userId = userId;
        profile.email = authUser.getEmail();
        profile.name = firstNonBlank(
                profileDto.displayName(),
                authUser.getDisplayName(),
                authUser.getUsername()
        );
        profile.brokerVendor = brokerStatus.brokerName();
        profile.brokerConnected = brokerStatus.connected() && brokerStatus.tokenValid();
        profile.createdAt = authUser.getCreatedAt() != null ? authUser.getCreatedAt().toEpochMilli() : 0L;
        profile.lastLoginAt = authUser.getLastLoginAt() != null ? authUser.getLastLoginAt().toEpochMilli() : 0L;
        profile.riskLimitsEnabled = profileDto.riskProfile() != null;
        profile.dailyLossLimit = defaultDailyLossLimit.negate();

        return profile;
    }

    @CacheEvict(value = "user_profile", key = "#userId")
    public void invalidateProfile(UUID userId) {
        log.debug("Invalidating user profile cache for userId: {}", userId);
    }

    @Cacheable(value = "risk_limits", key = "#userId", unless = "#result == null")
    @Transactional(readOnly = true)
    public UserRiskLimits getRiskLimits(UUID userId) {
        log.debug("Fetching risk limits for userId: {}", userId);

        if (!authUserRepository.existsById(userId)) {
            return null;
        }

        UserRiskLimits limits = new UserRiskLimits();
        limits.userId = userId;
        limits.dailyLossLimit = defaultDailyLossLimit.negate();
        limits.maxPositionSize = defaultMaxOrderNotional;
        limits.maxOrderValue = defaultMaxOrderNotional;
        limits.maxConcurrentOrders = defaultMaxConcurrentOrders;

        return limits;
    }

    @CacheEvict(value = "risk_limits", key = "#userId")
    public void invalidateRiskLimits(UUID userId) {
        log.debug("Invalidating risk limits cache for userId: {}", userId);
    }

    public static class UserRiskLimits {
        public UUID userId;
        public BigDecimal dailyLossLimit;
        public BigDecimal maxPositionSize;
        public BigDecimal maxOrderValue;
        public int maxConcurrentOrders;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
