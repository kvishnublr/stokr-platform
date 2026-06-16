package com.stokr.user.service;

import com.stokr.auth.domain.AuthUser;
import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.common.exception.NotFoundException;
import com.stokr.common.exception.BadRequestException;
import com.stokr.user.domain.UserProfile;
import com.stokr.user.dto.UserProfileDto;
import com.stokr.user.dto.UserProfileUpdateRequest;
import com.stokr.user.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserProfileRepository profileRepository;
    private final AuthUserRepository authUserRepository;

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
        AuthUser user = authUserRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        UserProfile p = profileRepository.findByUserIdAndDeletedFalse(userId)
                .orElseGet(() -> {
                    UserProfile np = new UserProfile();
                    np.setUserId(userId);
                    return profileRepository.save(np);
                });
        if (req.displayName() != null) {
            String displayName = req.displayName().trim();
            if (displayName.isEmpty()) {
                throw new BadRequestException("Display name cannot be empty");
            }
            p.setDisplayName(displayName);
            user.setDisplayName(displayName);
        }
        if (req.timezone() != null) {
            String timezone = req.timezone().trim();
            if (timezone.isEmpty()) {
                throw new BadRequestException("Timezone cannot be empty");
            }
            try {
                ZoneId.of(timezone);
            } catch (ZoneRulesException e) {
                throw new BadRequestException("Invalid timezone: " + timezone);
            }
            p.setTimezone(timezone);
        }
        if (req.preferencesJson() != null) {
            p.setPreferencesJson(req.preferencesJson());
        }
        authUserRepository.save(user);
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
