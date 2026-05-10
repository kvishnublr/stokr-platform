package com.stokr.user.service;

import com.stokr.common.exception.NotFoundException;
import com.stokr.user.domain.UserProfile;
import com.stokr.user.dto.UserProfileDto;
import com.stokr.user.dto.UserProfileUpdateRequest;
import com.stokr.user.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserProfileRepository profileRepository;

    @Transactional(readOnly = true)
    public UserProfileDto get(UUID userId) {
        UserProfile p = profileRepository.findByUserIdAndDeletedFalse(userId)
                .orElseThrow(() -> new NotFoundException("Profile not found"));
        return toDto(p);
    }

    @Transactional
    public UserProfileDto ensureProfile(UUID userId) {
        return profileRepository.findByUserIdAndDeletedFalse(userId)
                .map(this::toDto)
                .orElseGet(() -> {
                    UserProfile p = new UserProfile();
                    p.setUserId(userId);
                    return toDto(profileRepository.save(p));
                });
    }

    @Transactional
    public UserProfileDto update(UUID userId, UserProfileUpdateRequest req) {
        UserProfile p = profileRepository.findByUserIdAndDeletedFalse(userId)
                .orElseGet(() -> {
                    UserProfile np = new UserProfile();
                    np.setUserId(userId);
                    return profileRepository.save(np);
                });
        if (req.displayName() != null) {
            p.setDisplayName(req.displayName());
        }
        if (req.timezone() != null) {
            p.setTimezone(req.timezone());
        }
        if (req.preferencesJson() != null) {
            p.setPreferencesJson(req.preferencesJson());
        }
        if (req.subscriptionPlan() != null) {
            p.setSubscriptionPlan(req.subscriptionPlan());
        }
        if (req.riskProfile() != null) {
            p.setRiskProfile(req.riskProfile());
        }
        if (req.accountStatus() != null) {
            p.setAccountStatus(req.accountStatus());
        }
        if (req.brokerAccountPlaceholder() != null) {
            p.setBrokerAccountPlaceholder(req.brokerAccountPlaceholder());
        }
        if (req.tradingPreferencesJson() != null) {
            p.setTradingPreferencesJson(req.tradingPreferencesJson());
        }
        return toDto(profileRepository.save(p));
    }

    private UserProfileDto toDto(UserProfile p) {
        return new UserProfileDto(
                p.getId(),
                p.getUserId(),
                p.getDisplayName(),
                p.getTimezone(),
                p.getPreferencesJson(),
                p.getSubscriptionPlan(),
                p.getRiskProfile(),
                p.getAccountStatus(),
                p.getBrokerAccountPlaceholder(),
                p.getTradingPreferencesJson()
        );
    }
}
